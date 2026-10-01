package com.example.minikec.action

import com.example.minikec.action.adapter.output.redis.RedisRewardCounterAdapter
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StringRedisTemplate

class RedisRewardCounterResultTest {
    // 응답이 없으면 품절/반환 성공으로 간주하지 않아야 요청 선점을 안전하게 유지할 수 있다.
    private val adapter = RedisRewardCounterAdapter(mock(StringRedisTemplate::class.java))

    @Test fun `missing acquire result is an unknown outcome`() {
        assertThrows(IllegalArgumentException::class.java) { adapter.tryAcquire("counter", 10) }
    }

    @Test fun `missing release result is an unknown outcome`() {
        assertThrows(IllegalArgumentException::class.java) { adapter.release("counter") }
    }
}
