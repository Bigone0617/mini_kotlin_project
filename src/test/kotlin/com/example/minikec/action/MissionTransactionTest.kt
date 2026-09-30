package com.example.minikec.action

import com.example.minikec.action.adapter.output.mongodb.MongoMissionUnitOfWork
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
    private fun service(repository: UserPointRepositoryPort = points, time: Clock = clock) =
        ExecuteActionService(events, users, actions, repository, lock, counter, resources, time, DirectRewardUnitOfWork, transactions)
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
