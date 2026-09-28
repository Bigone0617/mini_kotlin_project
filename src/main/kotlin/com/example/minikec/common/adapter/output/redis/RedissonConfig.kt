package com.example.minikec.common.adapter.output.redis

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RedissonConfig(
    @Value("\${spring.data.redis.host}")
    private val host: String,

    @Value("\${spring.data.redis.port}")
    private val port: Int
) {

    @Bean(destroyMethod = "shutdown")
    fun redissonClient(): RedissonClient {

        val config = Config()

        config
            .useSingleServer()
            .setAddress(
                "redis://$host:$port"
            )

        return Redisson.create(config)
    }
}