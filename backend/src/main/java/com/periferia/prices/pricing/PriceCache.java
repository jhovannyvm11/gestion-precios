package com.periferia.prices.pricing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PriceCache {

    private static final Duration RETENTION = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PriceCache(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Optional<CachedPrice> get(String sku, UUID ruleId, long ruleVersion) {
        String value = redis.opsForValue().get(key(sku, ruleId, ruleVersion));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, CachedPrice.class));
        } catch (JsonProcessingException exception) {
            redis.delete(key(sku, ruleId, ruleVersion));
            return Optional.empty();
        }
    }

    public void put(String sku, UUID ruleId, long ruleVersion, PriceQuote quote) {
        try {
            redis.opsForValue().set(
                    key(sku, ruleId, ruleVersion),
                    objectMapper.writeValueAsString(new CachedPrice(quote, clock.instant())),
                    RETENTION
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No fue posible guardar la cotización en caché", exception);
        }
    }

    public void invalidate(String sku, UUID ruleId, long ruleVersion) {
        redis.delete(key(sku, ruleId, ruleVersion));
    }

    public boolean isFresh(CachedPrice cached, Instant now) {
        return !cached.quote().isStale(now, Duration.ofMinutes(15))
                && !cached.storedAt().isBefore(now.minusSeconds(60));
    }

    public boolean isFallbackUsable(CachedPrice cached, Instant now) {
        return !cached.storedAt().isBefore(now.minus(RETENTION));
    }

    private String key(String sku, UUID ruleId, long ruleVersion) {
        return "price:v1:" + sku + ":" + ruleId + ":" + ruleVersion;
    }

    public record CachedPrice(PriceQuote quote, Instant storedAt) {
    }
}