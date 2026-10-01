package com.example.minikec.action.adapter.output.redis

import com.example.minikec.action.application.port.output.RewardCounterPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

@Component
class RedisRewardCounterAdapter(
    private val redisTemplate: StringRedisTemplate
) : RewardCounterPort {

    private val acquireScript = DefaultRedisScript(
        """
        local current = tonumber(redis.call('GET', KEYS[1]) or '0')
        local maxCount = tonumber(ARGV[1])

        if current >= maxCount then
            return -1
        end

        return redis.call('INCR', KEYS[1])
        """.trimIndent(),
        Long::class.java
    )

    private val releaseScript = DefaultRedisScript(
        """
        local current = tonumber(redis.call('GET', KEYS[1]) or '0')

        if current <= 0 then
            return 0
        end

        return redis.call('DECR', KEYS[1])
        """.trimIndent(),
        Long::class.java
    )

    override fun tryAcquire(
        key: String,
        maxCount: Long
    ): Boolean {

        val result =
            StringRedisScriptSupport.execute(
                redisTemplate,
                acquireScript,
                key,
                maxCount.toString()
            )

        return requireNotNull(result) { "Reward counter acquisition outcome is unknown" } >= 0
    }

    override fun release(
        key: String
    ) {

        requireNotNull(StringRedisScriptSupport.execute(
            redisTemplate,
            releaseScript,
            key
        )) { "Reward counter release outcome is unknown" }
    }

    override fun getCount(
        key: String
    ): Long {

        return redisTemplate
            .opsForValue()
            .get(key)
            ?.toLongOrNull()
            ?: 0L
    }
}
