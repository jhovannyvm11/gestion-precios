package com.periferia.prices.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ProviderCircuitBreakerTest {

    private static final String UNREACHABLE_PROVIDER = "http://127.0.0.1:1";

    @Test
    void opensAfterHalfOfTenCallsFailAndThenRejectsWithoutCallingTheProvider() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build());
        ProviderAClient client = new ProviderAClient(RestClient.builder(), registry, UNREACHABLE_PROVIDER, 100);
        CircuitBreaker circuitBreaker = registry.circuitBreaker("providerA");

        for (int call = 0; call < 10; call++) {
            assertThrows(RuntimeException.class, () -> client.fetch("ABC-123"));
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertThrows(CallNotPermittedException.class, () -> client.fetch("ABC-123"));
        assertEquals(10, circuitBreaker.getMetrics().getNumberOfFailedCalls());
    }
}
