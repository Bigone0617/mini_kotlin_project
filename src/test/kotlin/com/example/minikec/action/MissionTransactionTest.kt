package com.example.minikec.action

import com.example.minikec.action.adapter.output.mongodb.MongoMissionUnitOfWork
import com.example.minikec.action.adapter.output.mongodb.MongoMissionExecutionRepositoryAdapter
import com.example.minikec.action.application.port.input.ExecuteActionCommand
import com.example.minikec.action.application.port.output.*
import com.example.minikec.action.application.service.ExecuteActionService
import com.example.minikec.action.domain.ActionRepeatNotAllowedException
import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.event.domain.*
import com.example.minikec.resource.application.port.output.ResourceRepositoryPort
import com.example.minikec.user.adapter.output.mongodb.*
import com.example.minikec.user.application.port.output.UserPointRepositoryPort
import com.example.minikec.user.domain.*
import com.mongodb.MongoException
import com.mongodb.client.MongoClients
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.*
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@EnabledIfEnvironmentVariable(named = "MINIKEC_MONGO_TEST_URI", matches = ".+")
class MissionTransactionTest {
    private val client = MongoClients.create(System.getenv("MINIKEC_MONGO_TEST_URI") ?: "mongodb://localhost:27017")
    private val dbName = "mini_kec_mission_test_" + UUID.randomUUID().toString().replace("-", "")
    private val template = MongoTemplate(client, dbName)
    private val names = DynamicCollectionNameProvider()
    private val participation = MongoParticipationUnitOfWork(template, names)
    private val transactions = MongoMissionUnitOfWork(template, names, participation)
    private val executions = MongoMissionExecutionRepositoryAdapter(template, names)
    private val users = MongoUserRepositoryAdapter(template, names)
    private val points = MongoUserPointRepositoryAdapter(template, names)
    private val actions = MongoUserActionRepositoryAdapter(template, names)
    private val events = mock(EventRepositoryPort::class.java)
    private val counter = mock(RewardCounterPort::class.java)
    private val resources = mock(ResourceRepositoryPort::class.java)
    // 동일 사용자 직렬화 계약을 재현한다. 실제 Redis 네트워크/락 장애 테스트는 아니다.
    private val mutex = ReentrantLock()
    private val lock = object : UserLockPort {
        override fun <T> withLock(lockKey: String, action: () -> T): T = mutex.withLock(action)
    }
    private val command = ExecuteActionCommand("g", "e", "mission", "external")
    private val initialTime = Instant.parse("2026-09-30T00:00:00Z")
    private val clock = Clock.fixed(initialTime, ZoneOffset.UTC)

    private fun prepare(goal: Long = 1, repeat: ActionRepeatType = ActionRepeatType.NONE): User {
        transactions.prepare("g", "e")
        val mission = Action("mission", "mission", ActionType.MISSION, ActionSubType.VISIT,
            actionRepeatType = repeat, goal = goal,
            pointRewards = listOf(ActionPoint("ticket", 5), ActionPoint("coin", 3)))
        `when`(events.findByEventKey("e")).thenReturn(Event("e", "g", "event", active = true,
            missionGroups = listOf(ActionGroup("missions", "missions", actions = listOf(mission)))))
        val user = users.save(User(eventKey = "e", gameKey = "g", externalUserId = "external"))
        points.earn("g", "e", user.id!!, "ticket", 10)
        return user
    }
    private fun service(
        repository: UserPointRepositoryPort = points,
        time: Clock = clock,
        userLock: UserLockPort = lock,
        work: MissionUnitOfWork = transactions,
        executionPort: MissionExecutionRepositoryPort = executions
    ) = ExecuteActionService(events, users, actions, repository, userLock, counter, resources, time, DirectRewardUnitOfWork, work, executionPort)
    private fun balance(user: User, key: String = "ticket") = points.findByUserIdAndPointKey("g", "e", user.id!!, key)?.currentPoint
    private fun count() = template.getCollection(names.userAction("g", "e")).countDocuments()
    private fun failSecond(): UserPointRepositoryPort = object : UserPointRepositoryPort by points {
        override fun earn(gameKey: String, eventKey: String, userId: String, pointKey: String, amount: Long): UserPoint {
            if (pointKey == "coin") throw IllegalStateException("second earning failed")
            return points.earn(gameKey, eventKey, userId, pointKey, amount)
        }
    }

    @AfterEach fun cleanup() {
        try { client.getDatabase(dbName).drop() } finally { client.close() }
    }

    @Test fun `second earning failure rolls back completion and first earning then retry succeeds`() {
        val user = prepare()
        assertThrows(IllegalStateException::class.java) { service(failSecond()).execute(command) }
        assertEquals(0L, count())
        assertEquals(10L, balance(user))
        assertNull(balance(user, "coin"))
        val result = service().execute(command)
        assertEquals(ActionStatus.COMPLETE, result.status)
        assertEquals(15L, balance(user))
        assertEquals(3L, balance(user, "coin"))
        assertEquals(1L, count())
        assertThrows(ActionRepeatNotAllowedException::class.java) { service().execute(command) }
        assertEquals(15L, balance(user))
        verifyNoInteractions(counter, resources)
    }

    @Test fun `failed completion preserves previous progress`() {
        val user = prepare(goal = 2)
        val first = service().execute(command)
        assertEquals(ActionStatus.PROGRESS, first.status)
        assertEquals(1L, first.currentProgress)
        assertTrue(first.points.isEmpty())
        assertEquals(10L, balance(user))
        assertThrows(IllegalStateException::class.java) { service(failSecond()).execute(command) }
        assertEquals(1L, count())
        assertEquals(ActionStatus.PROGRESS, actions.findLatestByUserIdAndActionId("g", "e", user.id!!, "mission")!!.status)
        assertEquals(10L, balance(user))
        assertEquals(ActionStatus.COMPLETE, service().execute(command).status)
        assertEquals(2L, count())
        assertEquals(15L, balance(user))
    }

    @Test fun `transient error after earning retries whole transaction without double credit`() {
        val user = prepare()
        val attempts = AtomicInteger()
        val flaky = object : UserPointRepositoryPort by points {
            override fun earn(gameKey: String, eventKey: String, userId: String, pointKey: String, amount: Long): UserPoint {
                val saved = points.earn(gameKey, eventKey, userId, pointKey, amount)
                if (pointKey == "coin" && attempts.incrementAndGet() == 1) {
                    throw MongoException(112, "simulated conflict").apply { addLabel("TransientTransactionError") }
                }
                return saved
            }
        }
        service(flaky).execute(command)
        assertEquals(2, attempts.get())
        assertEquals(1L, count())
        assertEquals(15L, balance(user))
        assertEquals(3L, balance(user, "coin"))
    }

    @Test fun `daily mission rejects same day and allows next UTC day`() {
        val user = prepare(repeat = ActionRepeatType.DAILY)
        service().execute(command)
        assertThrows(ActionRepeatNotAllowedException::class.java) { service().execute(command) }
        service(time = Clock.fixed(initialTime.plusSeconds(86400), ZoneOffset.UTC)).execute(command)
        assertEquals(2L, count())
        assertEquals(20L, balance(user))
        assertEquals(6L, balance(user, "coin"))
    }

    @Test fun `eight same user requests complete nonrepeatable mission only once under user lock`() {
        val user = prepare()
        val pool = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        val service = service()
        try {
            val tasks = (1..8).map { pool.submit(Callable {
                barrier.await(20, TimeUnit.SECONDS)
                try { service.execute(command); true } catch (exception: ActionRepeatNotAllowedException) { false }
            }) }
            assertEquals(1, tasks.count { it.get(45, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
        assertEquals(1L, count())
        assertEquals(15L, balance(user))
        assertEquals(3L, balance(user, "coin"))
    }

    @Test fun `same request replays original result while new request executes infinite mission`() {
        val user = prepare(repeat = ActionRepeatType.INFINITE)
        val firstCommand = command.copy(requestId = "request-1")
        val first = service().execute(firstCommand)
        assertEquals(first, service().execute(firstCommand))
        assertEquals(1L, count())
        assertEquals(15L, balance(user))
        val second = service().execute(command.copy(requestId = "request-2"))
        assertEquals(20L, second.points.first { it.pointKey == "ticket" }.currentPoint)
        assertEquals(first, service().execute(firstCommand))
        assertEquals(20L, balance(user))
        assertEquals(2L, count())
    }

    @Test fun `progress request replay does not advance progress twice`() {
        val user = prepare(goal = 2)
        val first = service().execute(command.copy(requestId = "progress-1"))
        assertEquals(first, service().execute(command.copy(requestId = "progress-1")))
        assertEquals(1L, count())
        assertEquals(10L, balance(user))
        val complete = service().execute(command.copy(requestId = "progress-2"))
        assertEquals(ActionStatus.COMPLETE, complete.status)
        assertEquals(complete, service().execute(command.copy(requestId = "progress-2")))
        assertEquals(15L, balance(user))
    }

    @Test fun `execution record failure rolls back all writes and allows same request retry`() {
        val user = prepare()
        val request = command.copy(requestId = "retry-me")
        val failingRecords = object : MissionExecutionRepositoryPort by executions {
            override fun insert(gameKey: String, eventKey: String, userId: String, actionId: String, requestId: String,
                result: com.example.minikec.action.application.port.input.ExecuteActionResult) {
                executions.insert(gameKey, eventKey, userId, actionId, requestId, result)
                throw IllegalStateException("result record failed")
            }
        }
        assertThrows(IllegalStateException::class.java) { service(executionPort = failingRecords).execute(request) }
        assertEquals(0L, count())
        assertEquals(10L, balance(user))
        assertNull(executions.find("g", "e", user.id!!, "mission", "retry-me"))
        service().execute(request)
        assertEquals(15L, balance(user))
        assertEquals(1L, count())
    }

    @Test fun `same request racing without user lock commits exactly once`() {
        val user = prepare(repeat = ActionRepeatType.INFINITE)
        val initialReads = AtomicInteger()
        val barrier = CyclicBarrier(8)
        val gated = object : MissionExecutionRepositoryPort by executions {
            override fun find(gameKey: String, eventKey: String, userId: String, actionId: String, requestId: String): com.example.minikec.action.application.port.input.ExecuteActionResult? {
                val number = initialReads.incrementAndGet()
                val result = executions.find(gameKey, eventKey, userId, actionId, requestId)
                if (number <= 8) { assertNull(result); barrier.await(20, TimeUnit.SECONDS) }
                return result
            }
        }
        val noLock = object : UserLockPort { override fun <T> withLock(lockKey: String, action: () -> T): T = action() }
        val service = service(userLock = noLock, executionPort = gated)
        val request = command.copy(requestId = "same-request")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map { pool.submit(Callable { service.execute(request) }) }
            val results = futures.map { it.get(45, TimeUnit.SECONDS) }
            assertTrue(results.all { it == results.first() })
        } finally { pool.shutdownNow(); check(pool.awaitTermination(20, TimeUnit.SECONDS)) }
        assertEquals(1L, count())
        assertEquals(15L, balance(user))
        assertEquals(3L, balance(user, "coin"))
        assertEquals(1L, template.getCollection(names.missionExecution("g", "e")).countDocuments())
    }

    @Test fun `committed request is replayed after lost response and event expiry`() {
        val user = prepare()
        val request = command.copy(requestId = "lost-response")
        val ambiguous = object : MissionUnitOfWork by transactions {
            override fun <T : Any> execute(action: () -> T): T {
                transactions.execute(action)
                throw MongoException(91, "simulated response loss").apply { addLabel("UnknownTransactionCommitResult") }
            }
        }
        assertThrows(MongoException::class.java) { service(work = ambiguous).execute(request) }
        val event = events.findByEventKey("e")!!
        `when`(events.findByEventKey("e")).thenReturn(event.copy(active = false, eventEndAt = initialTime))
        val result = service().execute(request)
        assertEquals(15L, result.points.first { it.pointKey == "ticket" }.currentPoint)
        assertEquals(15L, balance(user))
        assertEquals(1L, count())
        assertThrows(EventUnavailableException::class.java) { service().execute(command.copy(requestId = "new")) }
    }

    @Test fun `request identity is scoped to user action and event`() {
        val user = prepare(repeat = ActionRepeatType.INFINITE)
        val request = command.copy(requestId = "shared")
        val result = service().execute(request)
        val other = users.save(User(eventKey = "e", gameKey = "g", externalUserId = "other"))
        points.earn("g", "e", other.id!!, "ticket", 10)
        service().execute(request.copy(externalUserId = "other"))
        assertEquals(15L, balance(user))
        assertEquals(15L, balance(other))
        executions.insert("g", "e", user.id!!, "another-action", "shared", result.copy(actionId = "another-action"))
        transactions.prepare("g", "other-event")
        executions.insert("g", "other-event", user.id!!, "mission", "shared", result)
        assertNotNull(executions.find("g", "e", user.id!!, "another-action", "shared"))
        assertNotNull(executions.find("g", "other-event", user.id!!, "mission", "shared"))
        assertNull(executions.find("other-game", "e", user.id!!, "mission", "shared"))
        assertThrows(DuplicateKeyException::class.java) { executions.insert("g", "e", user.id!!, "mission", "shared", result) }
    }

    @Test fun `invalid request IDs are rejected before execution`() {
        for (id in listOf("", "   ", "a".repeat(129))) {
            assertThrows(com.example.minikec.action.domain.InvalidActionRequestException::class.java) {
                service().execute(command.copy(requestId = id))
            }
        }
        verifyNoInteractions(events, counter, resources)
    }

    @Test fun `request IDs on reward actions are explicitly rejected`() {
        prepare()
        val reward = Action("mission", "reward", ActionType.REWARD, ActionSubType.INSTANT_REWARD)
        `when`(events.findByEventKey("e")).thenReturn(Event("e", "g", "event", active = true,
            rewardGroups = listOf(ActionGroup("rewards", "rewards", actions = listOf(reward)))))
        assertThrows(com.example.minikec.action.domain.InvalidActionRequestException::class.java) {
            service().execute(command.copy(requestId = "unsupported"))
        }
        verifyNoInteractions(counter, resources)
    }

    @Test fun `duplicate key within transaction escapes without updating aborted session`() {
        val user = prepare()
        // 별도 unique 제약으로 적립 쓰기 실패를 확실하게 주입한다.
        template.getCollection(names.userPoint("g", "e")).createIndex(Document("eventKey", 1),
            com.mongodb.client.model.IndexOptions().unique(true))
        assertThrows(DuplicateKeyException::class.java) { service().execute(command) }
        assertEquals(0L, count())
        assertEquals(10L, balance(user))
        assertNull(balance(user, "coin"))
    }
}
