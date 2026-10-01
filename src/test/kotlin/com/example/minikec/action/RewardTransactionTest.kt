package com.example.minikec.action

import com.example.minikec.action.adapter.output.mongodb.MongoRewardUnitOfWork
import com.example.minikec.action.adapter.output.mongodb.MongoRewardExecutionRepositoryAdapter
import com.example.minikec.action.domain.RewardRequestPendingException
import com.example.minikec.action.application.port.input.ExecuteActionResult
import java.util.concurrent.CountDownLatch
import com.example.minikec.action.application.port.input.ExecuteActionCommand
import com.example.minikec.action.application.port.output.*
import com.example.minikec.action.application.service.ExecuteActionService
import com.example.minikec.action.domain.RewardCommitUncertainException
import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.event.domain.*
import com.example.minikec.resource.adapter.output.mongodb.*
import com.example.minikec.resource.domain.ResourceNotAvailableException
import com.example.minikec.resource.domain.ResourceStatus
import com.example.minikec.user.adapter.output.mongodb.*
import com.example.minikec.user.application.port.output.UserActionRepositoryPort
import com.example.minikec.user.domain.*
import com.mongodb.MongoException
import com.mongodb.client.MongoClients
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.*
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@EnabledIfEnvironmentVariable(named = "MINIKEC_MONGO_TEST_URI", matches = ".+")
class RewardTransactionTest {
    private val client = MongoClients.create(System.getenv("MINIKEC_MONGO_TEST_URI") ?: "mongodb://localhost:27017")
    private val dbName = "mini_kec_reward_test_" + UUID.randomUUID().toString().replace("-", "")
    private val template = MongoTemplate(client, dbName)
    private val names = DynamicCollectionNameProvider()
    private val participation = MongoParticipationUnitOfWork(template, names)
    private val transactions = MongoRewardUnitOfWork(template, names, participation)
    private val users = MongoUserRepositoryAdapter(template, names)
    private val points = MongoUserPointRepositoryAdapter(template, names)
    private val actions = MongoUserActionRepositoryAdapter(template, names)
    private val executions = MongoRewardExecutionRepositoryAdapter(template, names)
    private val resources = MongoResourceRepositoryAdapter(template)
    private val events = mock(EventRepositoryPort::class.java)
    private val count = AtomicInteger()
    private val acquisitions = AtomicInteger()
    private val releases = AtomicInteger()
    private var releaseFailure: RuntimeException? = null
    private val counter = object : RewardCounterPort {
        override fun tryAcquire(key: String, maxCount: Long): Boolean {
            acquisitions.incrementAndGet()
            while (true) {
                val current = count.get()
                if (current >= maxCount) return false
                if (count.compareAndSet(current, current + 1)) return true
            }
        }
        override fun release(key: String) {
            releases.incrementAndGet()
            releaseFailure?.let { throw it }
            count.decrementAndGet()
        }
        override fun getCount(key: String): Long = count.get().toLong()
    }
    // 별도 사용자 요청의 DB 정합성을 검사하므로 Redis User Lock은 이 테스트에서 생략한다.
    private val lock = object : UserLockPort {
        override fun <T> withLock(lockKey: String, action: () -> T): T = action()
    }
    private val resourceCollection = "g_e_commonResource"

    private fun prepare(repeat: ActionRepeatType = ActionRepeatType.NONE) {
        transactions.prepare("g", "e")
        val reward = Action("reward", "reward", ActionType.REWARD, ActionSubType.INSTANT_REWARD,
            goal = 3, actionRepeatType = repeat, totalCount = 8, itemRewards = listOf(ActionItem("coupon")))
        `when`(events.findByEventKey("e")).thenReturn(Event("e", "g", "event", active = true,
            points = listOf(PointDefinition("ticket", "ticket")),
            rewardGroups = listOf(ActionGroup("rewards", "rewards", actions = listOf(reward)))))
        template.save(ResourceDocument(id = "resource", eventKey = "e", itemKey = "coupon", key = "coupon-code"), resourceCollection)
    }
    private fun participant(external: String = "external"): User {
        val user = users.save(User(eventKey = "e", gameKey = "g", externalUserId = external))
        points.earn("g", "e", requireNotNull(user.id), "ticket", 10)
        return user
    }
    private fun service(repository: UserActionRepositoryPort = actions, work: RewardUnitOfWork = transactions,
        executionPort: RewardExecutionRepositoryPort = executions, counterPort: RewardCounterPort = counter) =
        ExecuteActionService(events, users, repository, points, lock, counterPort, resources, Clock.systemUTC(), work, DirectMissionUnitOfWork, org.mockito.Mockito.mock(com.example.minikec.action.application.port.output.MissionExecutionRepositoryPort::class.java), executionPort)
    private fun command(external: String = "external") = ExecuteActionCommand("g", "e", "reward", external)
    private fun balance(user: User) = points.findByUserIdAndPointKey("g", "e", user.id!!, "ticket")!!.currentPoint
    private fun resource() = template.findById("resource", ResourceDocument::class.java, resourceCollection)!!
    private fun actionCount() = template.getCollection(names.userAction("g", "e")).countDocuments()

    @AfterEach fun cleanup() {
        try { client.getDatabase(dbName).drop() } finally { client.close() }
    }

    @Test fun `failure after action write rolls back all mongo writes and permits a new request`() {
        prepare()
        val user = participant()
        val failure = IllegalStateException("after action write")
        val failing = object : UserActionRepositoryPort by actions {
            override fun save(gameKey: String, userAction: UserAction): UserAction {
                actions.save(gameKey, userAction)
                throw failure
            }
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { service(failing).execute(command()) })
        assertEquals(10L, balance(user))
        assertEquals(ResourceStatus.READY, resource().status)
        assertNull(resource().assignedUserId)
        assertEquals(0L, actionCount())
        assertEquals(0, count.get())
        assertEquals(1, releases.get())

        val result = service().execute(command())
        assertEquals(7L, result.points.single().currentPoint)
        assertEquals(7L, balance(user))
        assertEquals(ResourceStatus.ASSIGNED, resource().status)
        assertEquals(user.id, resource().assignedUserId)
        assertEquals(1L, actionCount())
        assertEquals(1, count.get())
    }

    @Test fun `transient failure retries mongo work but acquires redis slot only once`() {
        prepare()
        val user = participant()
        val attempts = AtomicInteger()
        val flaky = object : UserActionRepositoryPort by actions {
            override fun save(gameKey: String, userAction: UserAction): UserAction {
                val saved = actions.save(gameKey, userAction)
                if (attempts.incrementAndGet() == 1) throw MongoException(112, "simulated conflict").apply {
                    addLabel("TransientTransactionError")
                }
                return saved
            }
        }
        val request = command().copy(requestId = "transient-retry")
        val result = service(flaky).execute(request)
        assertEquals(result, service().execute(request))
        assertEquals(2, attempts.get())
        assertEquals(1, acquisitions.get())
        assertEquals(0, releases.get())
        assertEquals(1, count.get())
        assertEquals(7L, balance(user))
        assertEquals(1L, actionCount())
        assertEquals(ResourceStatus.ASSIGNED, resource().status)
    }

    @Test fun `one remaining resource permits only one of eight users to spend`() {
        prepare()
        val participants = (1..8).map { participant("external-$it") }
        val barrier = CyclicBarrier(8)
        val pool = Executors.newFixedThreadPool(8)
        val service = service()
        try {
            val tasks = participants.map { user -> pool.submit(Callable {
                barrier.await(20, TimeUnit.SECONDS)
                try { service.execute(command(user.externalUserId)); true }
                catch (exception: ResourceNotAvailableException) { false }
            }) }
            assertEquals(1, tasks.count { it.get(45, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
        assertEquals(1, participants.count { balance(it) == 7L })
        assertEquals(7, participants.count { balance(it) == 10L })
        assertEquals(1L, actionCount())
        assertEquals(1, count.get())
        assertEquals(7, releases.get())
        assertEquals(ResourceStatus.ASSIGNED, resource().status)
    }

    @Test fun `uncertain commit response preserves slot even when mongo data committed`() {
        prepare()
        val user = participant()
        val uncertain = object : RewardUnitOfWork by transactions {
            override fun <T : Any> execute(action: () -> T): T {
                transactions.execute(action)
                // 실제 네트워크 장애 대신 커밋 후 결과 불명확 응답을 주입한다.
                throw RewardCommitUncertainException(IllegalStateException("simulated lost acknowledgement"))
            }
        }
        assertThrows(RewardCommitUncertainException::class.java) { service(work = uncertain).execute(command()) }
        assertEquals(7L, balance(user))
        assertEquals(1L, actionCount())
        assertEquals(ResourceStatus.ASSIGNED, resource().status)
        assertEquals(1, count.get())
        assertEquals(0, releases.get())
    }

    @Test fun `unknown commit label is converted to uncertain outcome`() {
        prepare()
        assertThrows(RewardCommitUncertainException::class.java) {
            transactions.execute {
                throw MongoException(91, "simulated unknown commit").apply { addLabel("UnknownTransactionCommitResult") }
            }
        }
    }

    @Test fun `counter release failure preserves original error and rolled back mongo data`() {
        prepare()
        val user = participant()
        val failure = IllegalStateException("action failure")
        val failing = object : UserActionRepositoryPort by actions {
            override fun save(gameKey: String, userAction: UserAction): UserAction = throw failure
        }
        releaseFailure = IllegalStateException("redis unavailable")
        val thrown = assertThrows(IllegalStateException::class.java) { service(failing).execute(command()) }
        assertSame(failure, thrown)
        assertSame(releaseFailure, thrown.suppressed.single())
        assertEquals(10L, balance(user))
        assertEquals(ResourceStatus.READY, resource().status)
        assertEquals(0L, actionCount())
        assertEquals(1, count.get())
    }

    @Test fun `same reward ID replays original result after further spending and event closure`() {
        prepare(ActionRepeatType.INFINITE)
        val user = participant()
        template.save(ResourceDocument(id = "second", eventKey = "e", itemKey = "coupon", key = "second-code"), resourceCollection)
        val request = command().copy(requestId = "first")
        val first = service().execute(request)
        assertEquals(first, service().execute(request))
        assertEquals(4L, service().execute(request.copy(requestId = "second")).points.single().currentPoint)
        val event = events.findByEventKey("e")!!
        `when`(events.findByEventKey("e")).thenReturn(event.copy(active = false))
        assertEquals(first, service().execute(request))
        assertEquals(7L, first.points.single().currentPoint)
        assertEquals(4L, balance(user))
        assertEquals(2, acquisitions.get())
        assertEquals(2L, actionCount())
        assertEquals(2L, template.getCollection(names.rewardExecution("g", "e")).countDocuments())
        assertThrows(com.example.minikec.event.domain.EventUnavailableException::class.java) {
            service().execute(request.copy(requestId = "new"))
        }
    }

    @Test fun `receipt failure rolls back debit resource action and permits same ID retry`() {
        prepare()
        val user = participant()
        val request = command().copy(requestId = "retry")
        val failing = object : RewardExecutionRepositoryPort by executions {
            override fun complete(key: RewardRequestKey, result: ExecuteActionResult) {
                executions.complete(key, result)
                throw IllegalStateException("after receipt update")
            }
        }
        assertThrows(IllegalStateException::class.java) { service(executionPort = failing).execute(request) }
        assertEquals(10L, balance(user))
        assertEquals(ResourceStatus.READY, resource().status)
        assertEquals(0L, actionCount())
        assertEquals(0, count.get())
        assertNull(executions.find(RewardRequestKey("g", "e", user.id!!, "reward", "retry")))
        val result = service().execute(request)
        assertEquals(result, service().execute(request))
        assertEquals(7L, balance(user))
        assertEquals(1, count.get())
        assertEquals(1L, actionCount())
    }

    @Test fun `concurrent duplicate requests cannot acquire another slot without user lock`() {
        prepare(ActionRepeatType.INFINITE)
        val user = participant()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val paused = object : RewardCounterPort by counter {
            override fun tryAcquire(key: String, maxCount: Long): Boolean {
                val acquired = counter.tryAcquire(key, maxCount)
                entered.countDown()
                check(proceed.await(20, TimeUnit.SECONDS))
                return acquired
            }
        }
        val request = command().copy(requestId = "concurrent")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val winner = pool.submit(Callable { service(counterPort = paused).execute(request) })
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            val losers = (1..7).map { pool.submit(Callable {
                assertThrows(RewardRequestPendingException::class.java) { service().execute(request) }
            }) }
            losers.forEach { it.get(20, TimeUnit.SECONDS) }
            proceed.countDown()
            val result = winner.get(20, TimeUnit.SECONDS)
            assertEquals(result, service().execute(request))
        } finally {
            proceed.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
        assertEquals(1, acquisitions.get())
        assertEquals(0, releases.get())
        assertEquals(7L, balance(user))
        assertEquals(1L, actionCount())
    }

    @Test fun `lost commit acknowledgement replays result without reserving or spending twice`() {
        prepare()
        val user = participant()
        val uncertain = object : RewardUnitOfWork by transactions {
            override fun <T : Any> execute(action: () -> T): T {
                transactions.execute(action)
                throw RewardCommitUncertainException(IllegalStateException("lost acknowledgement"))
            }
        }
        val request = command().copy(requestId = "lost")
        assertThrows(RewardCommitUncertainException::class.java) { service(work = uncertain).execute(request) }
        assertEquals(7L, service().execute(request).points.single().currentPoint)
        assertEquals(7L, balance(user))
        assertEquals(1, acquisitions.get())
        assertEquals(0, releases.get())
        assertEquals(1L, actionCount())
    }

    @Test fun `unknown outcome without committed receipt blocks automatic reexecution`() {
        prepare()
        val user = participant()
        val uncertain = object : RewardUnitOfWork by transactions {
            override fun <T : Any> execute(action: () -> T): T =
                throw RewardCommitUncertainException(IllegalStateException("outcome unknown"))
        }
        val request = command().copy(requestId = "pending")
        assertThrows(RewardCommitUncertainException::class.java) { service(work = uncertain).execute(request) }
        assertThrows(RewardRequestPendingException::class.java) { service().execute(request) }
        assertEquals(10L, balance(user))
        assertEquals(0L, actionCount())
        assertEquals(1, acquisitions.get())
        assertEquals(0, releases.get())
        assertNotNull(executions.find(RewardRequestKey("g", "e", user.id!!, "reward", "pending")))
    }

    @Test fun `redis acquisition timeout preserves claim when reservation may have succeeded`() {
        prepare()
        val user = participant()
        val timeout = object : RewardCounterPort by counter {
            override fun tryAcquire(key: String, maxCount: Long): Boolean {
                counter.tryAcquire(key, maxCount)
                throw IllegalStateException("lost Redis reply")
            }
        }
        val request = command().copy(requestId = "redis-timeout")
        assertThrows(IllegalStateException::class.java) { service(counterPort = timeout).execute(request) }
        assertThrows(RewardRequestPendingException::class.java) { service().execute(request) }
        assertEquals(1, acquisitions.get())
        assertEquals(0, releases.get())
        assertEquals(10L, balance(user))
        assertEquals(0L, actionCount())
    }

    @Test fun `failed counter release keeps claim despite mongo rollback`() {
        prepare()
        val user = participant()
        val failing = object : UserActionRepositoryPort by actions {
            override fun save(gameKey: String, userAction: UserAction): UserAction = throw IllegalStateException("write failed")
        }
        releaseFailure = IllegalStateException("release failed")
        val request = command().copy(requestId = "release-failure")
        assertThrows(IllegalStateException::class.java) { service(repository = failing).execute(request) }
        assertThrows(RewardRequestPendingException::class.java) { service().execute(request) }
        assertEquals(10L, balance(user))
        assertEquals(0L, actionCount())
        assertEquals(1, acquisitions.get())
        assertEquals(1, releases.get())
    }

    @Test fun `request claim unique scope separates users actions events and games`() {
        prepare()
        val key = RewardRequestKey("g", "e", "u", "reward", "shared")
        assertTrue(executions.tryClaim(key))
        assertFalse(executions.tryClaim(key))
        assertTrue(executions.tryClaim(key.copy(userId = "other")))
        assertTrue(executions.tryClaim(key.copy(actionId = "other")))
        transactions.prepare("g", "other")
        assertTrue(executions.tryClaim(key.copy(eventKey = "other")))
        transactions.prepare("other", "e")
        assertTrue(executions.tryClaim(key.copy(gameKey = "other")))
    }

    @Test fun `eight simultaneous claims have exactly one owner`() {
        prepare()
        val key = RewardRequestKey("g", "e", "user", "reward", "race")
        val barrier = CyclicBarrier(8)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map { pool.submit(Callable {
                barrier.await(20, TimeUnit.SECONDS)
                executions.tryClaim(key)
            }) }
            assertEquals(1, tasks.count { it.get(20, TimeUnit.SECONDS) })
            assertEquals(1L, template.getCollection(names.rewardExecution("g", "e")).countDocuments())
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test fun `known sold out response removes claim without releasing another reservation`() {
        prepare()
        participant()
        count.set(8)
        val request = command().copy(requestId = "sold-out")
        assertThrows(com.example.minikec.action.domain.RewardSoldOutException::class.java) { service().execute(request) }
        assertEquals(0L, template.getCollection(names.rewardExecution("g", "e")).countDocuments())
        assertEquals(8, count.get())
        assertEquals(0, releases.get())
        count.set(7)
        service().execute(request)
        assertEquals(8, count.get())
        assertEquals(1L, actionCount())
    }
}
