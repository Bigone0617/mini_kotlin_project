package com.example.minikec.user

import com.example.minikec.user.adapter.output.mongodb.*
import com.example.minikec.user.domain.User
import com.example.minikec.user.domain.UserPoint
import com.example.minikec.user.application.port.output.UserPointRepositoryPort
import com.mongodb.MongoException
import com.example.minikec.event.application.port.output.EventRepositoryPort
import com.example.minikec.event.domain.Event
import com.example.minikec.event.domain.PointDefinition
import com.example.minikec.user.application.port.input.ParticipateEventCommand
import com.example.minikec.user.application.port.output.UserRepositoryPort
import com.example.minikec.user.application.service.ParticipateEventService
import org.mockito.Mockito.*
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import com.mongodb.client.MongoClients
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 실제 DB 테스트는 명시적으로 활성화하고, 매 테스트마다 별도의 임시 DB만 사용한다.
@EnabledIfEnvironmentVariable(named = "MINIKEC_MONGO_TEST_URI", matches = ".+")
class MongoUserUniqueIndexTest {
    private val client = MongoClients.create(System.getenv("MINIKEC_MONGO_TEST_URI") ?: "mongodb://localhost:27017")
    private val dbName = "mini_kec_index_test_" + UUID.randomUUID().toString().replace("-", "")
    private val template = MongoTemplate(client, dbName)
    private val names = DynamicCollectionNameProvider()
    private val unitOfWork = MongoParticipationUnitOfWork(template, names)
    private val adapter = MongoUserRepositoryAdapter(template, names)
    private val user = User(gameKey = "game", eventKey = "event", externalUserId = "external")
    private val collection = names.user("game", "event")

    @AfterEach fun cleanup() {
        try { client.getDatabase(dbName).drop() } finally { client.close() }
    }

    @Test fun `index blocks duplicate identity and allows updating same document`() {
        unitOfWork.prepare("game", "event")
        val saved = adapter.save(user)
        assertThrows(DuplicateKeyException::class.java) { adapter.save(user) }
        adapter.save(saved.copy(nickname = "updated"))
        assertEquals(1L, template.getCollection(collection).countDocuments())
        assertEquals("updated", adapter.findByExternalUserId("game", "event", "external")!!.nickname)
        val index = template.getCollection(collection).listIndexes().first { it.getBoolean("unique", false) }
        assertEquals(Document("externalUserId", 1), index["key"])
    }

    @Test fun `same external identity is allowed in different events and games`() {
        for ((g, e) in listOf("game" to "event", "game" to "other-event", "other-game" to "event")) unitOfWork.prepare(g, e)
        adapter.save(user)
        adapter.save(user.copy(eventKey = "other-event"))
        adapter.save(user.copy(gameKey = "other-game"))
        for ((game, event) in listOf("game" to "event", "game" to "other-event", "other-game" to "event")) {
            assertEquals(1L, template.getCollection(names.user(game, event)).countDocuments())
        }
    }

    @Test fun `concurrent first saves allow exactly one participant`() {
        unitOfWork.prepare("game", "event")
        val workers = 8
        val barrier = CyclicBarrier(workers)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val tasks = (1..workers).map {
                pool.submit(Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    try { adapter.save(user); true } catch (e: DuplicateKeyException) { false }
                })
            }
            assertEquals(1, tasks.count { it.get(30, TimeUnit.SECONDS) })
            assertEquals(1L, template.getCollection(collection).countDocuments())
        } finally { pool.shutdownNow() }
    }

    @RepeatedTest(3)
    fun `concurrent participation returns one user and initializes points only once`() {
        val workers = 8
        val initialReads = AtomicInteger()
        val duplicateSaves = AtomicInteger()
        val barrier = CyclicBarrier(workers)
        // 모든 요청의 첫 조회를 끝낸 후 저장을 시작해 경쟁 상황을 확실히 만든다.
        val racingUsers = object : UserRepositoryPort by adapter {
            override fun findByExternalUserId(gameKey: String, eventKey: String, externalUserId: String): User? {
                val readNumber = initialReads.incrementAndGet()
                val found = adapter.findByExternalUserId(gameKey, eventKey, externalUserId)
                if (readNumber <= workers) {
                    assertNull(found)
                    barrier.await(20, TimeUnit.SECONDS)
                }
                return found
            }

            override fun save(user: User): User = try {
                adapter.save(user)
            } catch (exception: DuplicateKeyException) {
                duplicateSaves.incrementAndGet()
                throw exception
            }
        }
        val events = mock(EventRepositoryPort::class.java)
        `when`(events.findByEventKey("event")).thenReturn(Event(
            eventKey = "event", gameKey = "game", name = "concurrent participation", active = true,
            points = listOf(PointDefinition("ticket", "ticket", initialPoint = 10))
        ))
        val pointAdapter = MongoUserPointRepositoryAdapter(template, names)
        val service = ParticipateEventService(events, racingUsers, pointAdapter, Clock.systemUTC(), unitOfWork)
        val command = ParticipateEventCommand("game", "event", "external", "participant")
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = (1..workers).map {
                pool.submit(Callable { service.participate(command) })
            }
            // 어떤 요청이든 예외를 반환하면 get()에서 테스트가 실패한다.
            val results = futures.map { it.get(40, TimeUnit.SECONDS) }
            val savedUser = requireNotNull(adapter.findByExternalUserId("game", "event", "external"))
            val userId = requireNotNull(savedUser.id)
            
            val count = template.getCollection(collection).countDocuments()
            assertEquals(1L, count)
            println("count: $count")
            
            assertTrue(results.all { it.user.id == userId })
            println("results: ${results.map { it.user.id }}")
            
            
            assertEquals(1, results.count { !it.alreadyParticipated })
            println("results: ${results.map { it.toString() }}")
            
            assertEquals(workers - 1, results.count { it.alreadyParticipated })
            
            assertEquals(workers - 1, duplicateSaves.get())
            println("duplicateSaves: $duplicateSaves")
            
            assertTrue(results.filter { it.alreadyParticipated }.all { it.points.size == 1 })
            println("results: ${results.filter { it.alreadyParticipated }.map { it.points }}")

            assertEquals(1L, template.getCollection(names.userPoint("game", "event")).countDocuments())
            val point = requireNotNull(pointAdapter.findByUserIdAndPointKey("game", "event", userId, "ticket"))
            assertEquals(10L, point.totalPoint)
            assertEquals(10L, point.currentPoint)
            assertTrue(results.all { it.points == listOf(point) })
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(20, TimeUnit.SECONDS)) { "Participation workers did not stop" }
        }
    }

    @Test fun `second point failure rolls back user and first point and allows retry`() {
        val events = mock(EventRepositoryPort::class.java)
        `when`(events.findByEventKey("event")).thenReturn(Event(
            eventKey = "event", gameKey = "game", name = "rollback", active = true,
            points = listOf(PointDefinition("first", "first", 10), PointDefinition("second", "second", 20))
        ))
        val realPoints = MongoUserPointRepositoryAdapter(template, names)
        val failure = IllegalStateException("second point failed")
        val failingPoints = object : UserPointRepositoryPort by realPoints {
            override fun save(gameKey: String, userPoint: UserPoint): UserPoint {
                if (userPoint.pointKey == "second") throw failure
                return realPoints.save(gameKey, userPoint)
            }
        }
        val command = ParticipateEventCommand("game", "event", "external", null)
        val failingService = ParticipateEventService(events, adapter, failingPoints, Clock.systemUTC(), unitOfWork)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { failingService.participate(command) })
        assertEquals(0L, template.getCollection(collection).countDocuments())
        assertEquals(0L, template.getCollection(names.userPoint("game", "event")).countDocuments())

        val service = ParticipateEventService(events, adapter, realPoints, Clock.systemUTC(), unitOfWork)
        val result = service.participate(command)
        assertFalse(result.alreadyParticipated)
        assertEquals(listOf(10L, 20L), result.points.map { it.currentPoint })
        assertEquals(1L, template.getCollection(collection).countDocuments())
        assertEquals(2L, template.getCollection(names.userPoint("game", "event")).countDocuments())
        assertTrue(service.participate(command).alreadyParticipated)
        assertEquals(2L, template.getCollection(names.userPoint("game", "event")).countDocuments())
    }

    @Test fun `repeat participation returns stored balances only for requested user and event`() {
        val events = mock(EventRepositoryPort::class.java)
        `when`(events.findByEventKey("event")).thenReturn(Event(
            eventKey = "event", gameKey = "game", name = "balances", active = true,
            points = listOf(PointDefinition("ticket", "ticket", 10), PointDefinition("coin", "coin", 20))
        ))
        val pointAdapter = MongoUserPointRepositoryAdapter(template, names)
        val service = ParticipateEventService(events, adapter, pointAdapter, Clock.systemUTC(), unitOfWork)
        val command = ParticipateEventCommand("game", "event", "external", null)
        val created = service.participate(command)
        val id = requireNotNull(created.user.id)
        val updated = pointAdapter.save("game", created.points.first { it.pointKey == "ticket" }.copy(totalPoint = 30, currentPoint = 7))
        pointAdapter.save("game", UserPoint(eventKey = "event", userId = "other-user", pointKey = "ticket", currentPoint = 999))
        pointAdapter.save("game", UserPoint(eventKey = "other-event", userId = id, pointKey = "ticket", currentPoint = 999))
        pointAdapter.save("other-game", UserPoint(eventKey = "event", userId = id, pointKey = "ticket", currentPoint = 999))

        val result = service.participate(command)
        assertTrue(result.alreadyParticipated)
        assertEquals(listOf("coin", "ticket"), result.points.map { it.pointKey })
        assertEquals(updated, result.points.single { it.pointKey == "ticket" })
        assertEquals(20L, result.points.single { it.pointKey == "coin" }.currentPoint)
        assertEquals(1L, template.getCollection(collection).countDocuments())
        assertEquals(3L, template.getCollection(names.userPoint("game", "event")).countDocuments())
    }

    @Test fun `transient transaction error retries after rollback`() {
        unitOfWork.prepare("game", "event")
        var attempts = 0
        val result = unitOfWork.execute {
            val saved = adapter.save(user)
            if (++attempts == 1) {
                throw MongoException(112, "simulated write conflict").apply {
                    addLabel("TransientTransactionError")
                }
            }
            saved
        }
        assertEquals(2, attempts)
        assertEquals(result.id, adapter.findByExternalUserId("game", "event", "external")!!.id)
        assertEquals(1L, template.getCollection(collection).countDocuments())
    }

    @Test fun `transient retries are bounded`() {
        unitOfWork.prepare("game", "event")
        var attempts = 0
        assertThrows(MongoException::class.java) {
            unitOfWork.execute {
                adapter.save(user)
                attempts++
                throw MongoException(112, "persistent conflict").apply { addLabel("TransientTransactionError") }
            }
        }
        assertEquals(5, attempts)
        assertEquals(0L, template.getCollection(collection).countDocuments())
    }

    @Test fun `unknown commit label does not replay work`() {
        var attempts = 0
        assertThrows(MongoException::class.java) {
            unitOfWork.execute {
                attempts++
                // 라벨 분기만 검증한다. 실제 네트워크 커밋 장애를 재현하는 테스트는 아니다.
                throw MongoException(91, "unknown result").apply {
                    addLabel("TransientTransactionError")
                    addLabel("UnknownTransactionCommitResult")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test fun `existing duplicate data prevents index creation and new writes`() {
        template.getCollection(collection).insertMany(listOf(
            Document("externalUserId", "duplicate"), Document("externalUserId", "duplicate")
        ))
        assertThrows(DataAccessException::class.java) { unitOfWork.prepare("game", "event") }
        assertEquals(2L, template.getCollection(collection).countDocuments())
        assertEquals(0L, template.getCollection(collection).countDocuments(Document("externalUserId", "external")))
    }
}
