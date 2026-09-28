package com.example.minikec.action.adapter.output.redis

import com.example.minikec.action.application.port.output.UserLockPort
import com.example.minikec.action.domain.UserLockAcquisitionException
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class RedissonUserLockAdapter(
    private val redissonClient: RedissonClient
) : UserLockPort {

    override fun <T> withLock(
        lockKey: String,
        action: () -> T
    ): T {

        val lock =
            redissonClient.getLock(lockKey)

        /*
         * 최대 3초 동안 Lock 획득을 기다린다.
         *
         * leaseTime을 직접 지정하지 않았기 때문에
         * Redisson Watchdog이 Lock TTL을 관리한다.
         */
        val acquired =
            lock.tryLock(
                3,
                TimeUnit.SECONDS
            )

        if (!acquired) {
            println("LOCK FAILED: $lockKey")
            
            throw UserLockAcquisitionException(
                lockKey
            )
        }
        
        println("LOCK ACQUIRED: $lockKey")

        try {
            return action()

        } finally {

            if (lock.isHeldByCurrentThread) {
                lock.unlock()
                
                 println("LOCK RELEASED: $lockKey")
            }
        }
    }
}