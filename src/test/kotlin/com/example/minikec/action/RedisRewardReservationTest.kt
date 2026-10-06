package com.example.minikec.action

import com.example.minikec.action.adapter.output.redis.RedisRewardCounterAdapter
import com.example.minikec.action.application.port.output.ReservationState
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "MINIKEC_REDIS_TEST_HOST", matches = ".+")
class RedisRewardReservationTest {
    private val factory = LettuceConnectionFactory(System.getenv("MINIKEC_REDIS_TEST_HOST"),
        System.getenv("MINIKEC_REDIS_TEST_PORT")?.toInt() ?: 6379).apply { afterPropertiesSet(); start() }
    private val template = StringRedisTemplate(factory)
    private val adapter = RedisRewardCounterAdapter(template)
    private val key = "mini-kec-test:" + UUID.randomUUID()

    @AfterEach fun cleanup() {
        try { template.delete(listOf(key, "$key:reservations")) } finally { factory.destroy() }
    }

    @Test fun `same reservation and release remain idempotent after lost replies`() {
        assertTrue(adapter.reserve(key, "a", 2))
        assertTrue(adapter.reserve(key, "a", 2))
        assertTrue(adapter.reserve(key, "b", 2))
        assertFalse(adapter.reserve(key, "c", 2))
        assertEquals(2L, adapter.getCount(key))
        adapter.releaseReservation(key, "a")
        adapter.releaseReservation(key, "a")
        assertEquals(1L, adapter.getCount(key))
        assertEquals(ReservationState.RELEASED, adapter.reservationState(key, "a"))
        // 종료된 token은 재예약하지 않는다. 새 시도는 새 token을 사용한다.
        assertFalse(adapter.reserve(key, "a", 2))
        assertFalse(adapter.reserve(key, "c", 2))
        assertTrue(adapter.reserve(key, "new-attempt", 2))
    }

    @Test fun `missing or inconsistent evidence never decrements other requests`() {
        assertTrue(adapter.reserve(key, "owner", 2))
        assertThrows(IllegalStateException::class.java) { adapter.releaseReservation(key, "unknown") }
        assertEquals(1L, adapter.getCount(key))
        template.delete(key)
        assertThrows(IllegalStateException::class.java) { adapter.releaseReservation(key, "owner") }
        assertEquals(ReservationState.RESERVED, adapter.reservationState(key, "owner"))
    }

    @Test fun `concurrent duplicate and distinct reservations respect capacity and return only once`() {
        val pool = Executors.newFixedThreadPool(8)
        fun run(block: (Int) -> Boolean): List<Boolean> {
            val barrier = CyclicBarrier(8)
            return (1..8).map { index -> pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); block(index) }) }
                .map { it.get(20, TimeUnit.SECONDS) }
        }
        try {
            assertTrue(run { adapter.reserve(key, "same", 4) }.all { it })
            assertEquals(1L, adapter.getCount(key))
            assertEquals(3, run { adapter.reserve(key, "different-$it", 4) }.count { it })
            assertEquals(4L, adapter.getCount(key))
            run { adapter.releaseReservation(key, "same"); true }
            assertEquals(3L, adapter.getCount(key))
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
    @Test fun `zero stock is sold out and corrupt reservation state is not disguised as sold out`() {
        assertFalse(adapter.reserve(key, "zero", 0))
        assertEquals(0L, adapter.getCount(key))
        template.opsForHash<String, String>().put("$key:reservations", "corrupt", "INVALID")
        assertThrows(IllegalStateException::class.java) { adapter.reserve(key, "corrupt", 1) }
        assertEquals(0L, adapter.getCount(key))
    }

}
