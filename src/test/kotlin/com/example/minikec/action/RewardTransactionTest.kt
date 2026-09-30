package com.example.minikec.action

import com.example.minikec.action.adapter.output.mongodb.MongoRewardUnitOfWork
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

    private fun prepare() {
        transactions.prepare("g", "e")
        val reward = Action("reward", "reward", ActionType.REWARD, ActionSubType.INSTANT_REWARD,
            goal = 3, totalCount = 8, itemRewards = listOf(ActionItem("coupon")))
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
    private fun service(repository: UserActionRepositoryPort = actions, work: RewardUnitOfWork = transactions) =
        ExecuteActionService(events, users, repository, points, lock, counter, resources, Clock.systemUTC(), work, DirectMissionUnitOfWork, org.mockito.Mockito.mock(com.example.minikec.action.application.port.output.MissionExecutionRepositoryPort::class.java))
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
        service(flaky).execute(command())
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
}
