package com.example.minikec.user

import com.example.minikec.user.adapter.output.mongodb.*
import com.example.minikec.user.domain.User
import com.mongodb.client.MongoClients
import org.bson.Document
import org.junit.jupiter.api.*
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

    @Test fun `existing duplicate data prevents index creation and new writes`() {
        template.getCollection(collection).insertMany(listOf(
            Document("externalUserId", "duplicate"), Document("externalUserId", "duplicate")
        ))
        assertThrows(DataAccessException::class.java) { adapter.save(user) }
        assertEquals(2L, template.getCollection(collection).countDocuments())
        assertEquals(0L, template.getCollection(collection).countDocuments(Document("externalUserId", "external")))
    }
}
