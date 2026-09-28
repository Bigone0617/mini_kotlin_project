package com.example.minikec.action.adapter.output.redis;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Kotlin cannot call {@link StringRedisTemplate#execute(RedisScript, List, Object...)}
 * under {@code -Xjsr305=strict} because {@code List<@NonNull K>} does not match
 * {@code kotlin.collections.List}.
 */
final class StringRedisScriptSupport {

    private StringRedisScriptSupport() {}

    static @Nullable Long execute(
        StringRedisTemplate redisTemplate,
        RedisScript<Long> script,
        String key,
        Object... args
    ) {
        return redisTemplate.execute(script, List.of(key), args);
    }
}
