package com.example.minikec.user

import com.example.minikec.user.adapter.output.mongodb.*
import com.example.minikec.user.domain.User
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
    private val adapter = MongoUserRepositoryAdapter(template, names)
    private val user = User(gameKey = "game", eventKey = "event", externalUserId = "external")
    private val collection = names.user("game", "event")

    @AfterEach fun cleanup() {
        try { client.getDatabase(dbName).drop() } finally { client.close() }
    }

    @Test fun `index blocks duplicate identity and allows updating same document`() {
        val saved = adapter.save(user)
        assertThrows(DuplicateKeyException::class.java) { adapter.save(user) }
        adapter.save(saved.copy(nickname = "updated"))
        assertEquals(1L, template.getCollection(collection).countDocuments())
        assertEquals("updated", adapter.findByExternalUserId("game", "event", "external")!!.nickname)
        val index = template.getCollection(collection).listIndexes().first { it.getBoolean("unique", false) }
        assertEquals(Document("externalUserId", 1), index["key"])
    }

    @Test fun `same external identity is allowed in different events and games`() {
        adapter.save(user)
        adapter.save(user.copy(eventKey = "other-event"))
        adapter.save(user.copy(gameKey = "other-game"))
        for ((game, event) in listOf("game" to "event", "game" to "other-event", "other-game" to "event")) {
            assertEquals(1L, template.getCollection(names.user(game, event)).countDocuments())
        }
    }

    @Test fun `concurrent first saves allow exactly one participant`() {
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
        val service = ParticipateEventService(events, racingUsers, pointAdapter, Clock.systemUTC())
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
            
            assertTrue(results.filter { it.alreadyParticipated }.all { it.points.isEmpty() })
            println("results: ${results.filter { it.alreadyParticipated }.map { it.points }}")

            assertEquals(1L, template.getCollection(names.userPoint("game", "event")).countDocuments())
            val point = requireNotNull(pointAdapter.findByUserIdAndPointKey("game", "event", userId, "ticket"))
            assertEquals(10L, point.totalPoint)
            assertEquals(10L, point.currentPoint)
            assertEquals(listOf(point), results.single { !it.alreadyParticipated }.points)
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(20, TimeUnit.SECONDS)) { "Participation workers did not stop" }
        }
    }

    @Test fun `existing duplicate data prevents index creation and new writes`() {
        template.getCollection(collection).insertMany(listOf(
            Document("externalUserId", "duplicate"), Document("externalUserId", "duplicate")
        ))
        assertThrows(DataAccessException::class.java) { adapter.save(user) }
        assertEquals(2L, template.getCollection(collection).countDocuments())
        assertEquals(0L, template.getCollection(collection).countDocuments(Document("externalUserId", "external")))
    }
}
