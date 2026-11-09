package com.periferia.prices.pricing;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class ProviderBRateLimiter {

    private static final String KEY = "rate-limit:provider-b:events";
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local milliseconds = now[1] * 1000 + math.floor(now[2] / 1000)
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', milliseconds - window)
            if redis.call('ZCARD', KEYS[1]) >= limit then
                return 0
            end
            local sequence = redis.call('INCR', KEYS[2])
            redis.call('ZADD', KEYS[1], milliseconds, milliseconds .. ':' .. sequence)
            redis.call('PEXPIRE', KEYS[1], window * 2)
            redis.call('PEXPIRE', KEYS[2], window * 2)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String key;

    @Autowired
    public ProviderBRateLimiter(StringRedisTemplate redis) {
        this(redis, KEY);
    }

    ProviderBRateLimiter(StringRedisTemplate redis, String key) {
        this.redis = redis;
        this.key = key;
    }

    public boolean tryAcquire() {
        Long allowed = redis.execute(TOKEN_BUCKET, List.of(key, key + ":sequence"), "5", "1000");
        return Long.valueOf(1).equals(allowed);
    }
}