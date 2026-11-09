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
public class ProviderBClient {

    private final Semaphore bulkhead = new Semaphore(5);
    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public ProviderBClient(
            RestClient.Builder builder,
            CircuitBreakerRegistry circuitBreakers,
            @Value("${providers.b.url}") String baseUrl,
            @Value("${providers.b.timeout-ms:650}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(timeoutMs, 100));
        requestFactory.setReadTimeout(timeoutMs);
        restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        circuitBreaker = circuitBreakers.circuitBreaker("providerB");
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
        ProviderBResponse response = restClient.get()
                .uri("/b/prices/{sku}", sku)
                .retrieve()
                .body(ProviderBResponse.class);
        if (response == null) {
            throw new IllegalStateException("Proveedor B devolvió una respuesta vacía");
        }
        return new PriceQuote(
                response.itemCode(),
                response.cost(),
                response.curr(),
                PriceProvider.PROVIDER_B,
                Instant.ofEpochSecond(response.timestamp())
        );
    }

    private record ProviderBResponse(String itemCode, BigDecimal cost, String curr, long timestamp) {
    }
}