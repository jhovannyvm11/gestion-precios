package com.periferia.prices.pricing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.Assumptions;
import java.util.UUID;

class ProviderBRateLimiterRedisTest {

    private static RedisConnectionFactory connectionFactory;
    private static ProviderBRateLimiter rateLimiter;

    @BeforeAll
    static void connectToRedis() {
        String host = System.getenv().getOrDefault("REDIS_TEST_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6379"));
        LettuceConnectionFactory lettuceConnectionFactory = new LettuceConnectionFactory(host, port);
        lettuceConnectionFactory.afterPropertiesSet();
        connectionFactory = lettuceConnectionFactory;
        try {
            connectionFactory.getConnection().ping();
        } catch (RuntimeException exception) {
            lettuceConnectionFactory.destroy();
            Assumptions.assumeTrue(false, "Inicie Redis con docker compose para ejecutar la prueba de rate limit");
        }

        StringRedisTemplate redisTemplate = new StringRedisTemplate(lettuceConnectionFactory);
        redisTemplate.afterPropertiesSet();
        rateLimiter = new ProviderBRateLimiter(redisTemplate, "rate-limit-test:" + UUID.randomUUID());
    }

    @AfterAll
    static void closeRedisConnection() {
        if (connectionFactory instanceof LettuceConnectionFactory lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy();
        }
    }

    @Test
    void allowsFiveCallsAndRejectsTheSixthInTheSameWindow() {
        for (int request = 0; request < 5; request++) {
            assertTrue(rateLimiter.tryAcquire());
        }

        assertFalse(rateLimiter.tryAcquire());
    }
}