package com.periferia.prices.pricing;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProviderAClient {

    private final Semaphore bulkhead = new Semaphore(32);
    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public ProviderAClient(
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            @Value("${providers.a.url}") String baseUrl,
            @Value("${providers.a.timeout-ms:250}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 100));
        requestFactory.setReadTimeout(timeoutMs);
        restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        circuitBreaker = circuitBreakers.circuitBreaker("providerA");
    }

    public PriceQuote fetch(String sku) {
        if (!bulkhead.tryAcquire()) {
            throw new ProviderUnavailableException(sku);
        }
        try {
            return circuitBreaker.executeSupplier(() -> request(sku));
        } finally {
            bulkhead.release();
        }
    }

    private PriceQuote request(String sku) {
        ProviderAResponse response = restClient.get()
                .uri("/a/prices/{sku}", sku)
                .retrieve()
                .body(ProviderAResponse.class);
        if (response == null) {
            throw new IllegalStateException("Proveedor A devolvió una respuesta vacía");
        }
        return new PriceQuote(
                response.sku(),
                response.basePrice(),
                response.currency(),
                PriceProvider.PROVIDER_A,
                Instant.parse(response.lastUpdated())
        );
    }

    private record ProviderAResponse(String sku, BigDecimal basePrice, String currency, String lastUpdated) {
    }
}