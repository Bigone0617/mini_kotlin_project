package com.example.minikec.action.adapter.output.redis

import com.example.minikec.action.application.port.output.RewardCounterPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

@Component
class RedisRewardCounterAdapter(
    private val redisTemplate: StringRedisTemplate
) : RewardCounterPort {

    private val reserveScript = DefaultRedisScript("""
        local state = redis.call('HGET', KEYS[2], ARGV[1])
        if state == 'RESERVED' then return 1 end
        if state == 'RELEASED' or state == 'REJECTED' then return 0 end
        if state then return -1 end
        local current = tonumber(redis.call('GET', KEYS[1]) or '0')
        if not current or current < 0 or current ~= math.floor(current) then return -1 end
        if current >= tonumber(ARGV[2]) then
            redis.call('HSET', KEYS[2], ARGV[1], 'REJECTED')
            return 0
        end
        redis.call('INCR', KEYS[1])
        redis.call('HSET', KEYS[2], ARGV[1], 'RESERVED')
        return 1
    """.trimIndent(), Long::class.java)

    private val releaseReservationScript = DefaultRedisScript("""
        local state = redis.call('HGET', KEYS[2], ARGV[1])
        if not state then return -1 end
        if state == 'RELEASED' or state == 'REJECTED' then return 0 end
        if state ~= 'RESERVED' then return -1 end
        local current = tonumber(redis.call('GET', KEYS[1]) or '0')
        if current <= 0 then return -1 end
        redis.call('DECR', KEYS[1])
        redis.call('HSET', KEYS[2], ARGV[1], 'RELEASED')
        return 1
    """.trimIndent(), Long::class.java)

    override fun reserve(key: String, token: String, maxCount: Long): Boolean {
        require(token.isNotBlank() && maxCount >= 0)
        val result = requireNotNull(StringRedisScriptSupport.executeKeys(redisTemplate, reserveScript,
            key, "$key:reservations", token, maxCount.toString())) { "Reservation outcome is unknown" }
        check(result == 0L || result == 1L) { "Invalid reservation result" }
        return result == 1L
    }

    override fun releaseReservation(key: String, token: String) {
        require(token.isNotBlank())
        val result = requireNotNull(StringRedisScriptSupport.executeKeys(redisTemplate, releaseReservationScript,
            key, "$key:reservations", token)) { "Reservation release outcome is unknown" }
        check(result == 0L || result == 1L) { "Reservation evidence missing or inconsistent" }
    }

    override fun reservationState(key: String, token: String): com.example.minikec.action.application.port.output.ReservationState {
        val value = redisTemplate.opsForHash<String, String>().get("$key:reservations", token)
        return value?.let { com.example.minikec.action.application.port.output.ReservationState.valueOf(it) }
            ?: com.example.minikec.action.application.port.output.ReservationState.MISSING
    }

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
