package com.periferia.prices.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.periferia.prices.rules.PreferredProvider;
import com.periferia.prices.rules.ProductRule;
import com.periferia.prices.rules.ProductRuleService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PriceLookupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Mock
    private ProductRuleService rules;
    @Mock
    private ProviderAClient providerA;
    @Mock
    private ProviderBClient providerB;
    @Mock
    private ProviderBRateLimiter rateLimiter;
    @Mock
    private PriceCache cache;

    private PriceLookupService service;

    @BeforeEach
    void setUp() {
        service = new PriceLookupService(
                rules,
                providerA,
                providerB,
                rateLimiter,
                cache,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run
        );
    }

    @Test
    void returnsFreshCacheWithoutCallingProviders() {
        ProductRule rule = rule(PreferredProvider.AUTO);
        PriceQuote quote = quote(PriceProvider.PROVIDER_A, NOW.minusSeconds(30));
        when(rules.findActiveBySku("ABC-123")).thenReturn(rule);
        PriceCache.CachedPrice cached = new PriceCache.CachedPrice(quote, NOW.minusSeconds(30));
        when(cache.get("ABC-123", rule.getId(), rule.getVersion())).thenReturn(Optional.of(cached));
        when(cache.isFresh(cached, NOW)).thenReturn(true);

        PriceResponse response = service.lookup("ABC-123");

        assertEquals("CACHE", response.source());
        assertEquals(new BigDecimal("115.00"), response.finalPrice());
        verify(providerA, never()).fetch(any());
        verify(providerB, never()).fetch(any());
    }

    @Test
    void failsOverToProviderBWhenProviderAIsDown() {
        ProductRule rule = rule(PreferredProvider.AUTO);
        PriceQuote quote = quote(PriceProvider.PROVIDER_B, NOW.minusSeconds(10));
        when(rules.findActiveBySku("ABC-123")).thenReturn(rule);
        when(cache.get("ABC-123", rule.getId(), rule.getVersion())).thenReturn(Optional.empty());
        when(providerA.fetch("ABC-123")).thenThrow(new IllegalStateException("Proveedor A caído"));
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(providerB.fetch("ABC-123")).thenReturn(quote);

        PriceResponse response = service.lookup("ABC-123");

        assertEquals("PROVIDER_B", response.source());
        assertEquals(new BigDecimal("115.00"), response.finalPrice());
        verify(cache).put("ABC-123", rule.getId(), rule.getVersion(), quote);
    }

    @Test
    void returnsControlledUnavailableWhenBothProvidersFailWithoutFallback() {
        ProductRule rule = rule(PreferredProvider.AUTO);
        when(rules.findActiveBySku("ABC-123")).thenReturn(rule);
        when(cache.get("ABC-123", rule.getId(), rule.getVersion())).thenReturn(Optional.empty());
        when(providerA.fetch("ABC-123")).thenThrow(new IllegalStateException("Proveedor A caído"));
        when(rateLimiter.tryAcquire()).thenReturn(true);
        when(providerB.fetch("ABC-123")).thenThrow(new IllegalStateException("Proveedor B caído"));

        assertThrows(ProviderUnavailableException.class, () -> service.lookup("ABC-123"));
    }

    @Test
    void fallsBackToStaleCacheWhenProviderBRateLimitIsReached() {
        ProductRule rule = rule(PreferredProvider.PROVIDER_B);
        PriceQuote staleQuote = quote(PriceProvider.PROVIDER_A, NOW.minusSeconds(120));
        when(rules.findActiveBySku("ABC-123")).thenReturn(rule);
        PriceCache.CachedPrice cached = new PriceCache.CachedPrice(staleQuote, NOW.minusSeconds(10));
        when(cache.get(eq("ABC-123"), eq(rule.getId()), any(Long.class))).thenReturn(Optional.of(cached));
        when(cache.isFresh(cached, NOW)).thenReturn(false);
        when(rateLimiter.tryAcquire()).thenReturn(false);
        when(cache.isFallbackUsable(cached, NOW)).thenReturn(true);

        PriceResponse response = service.lookup("ABC-123");

        assertEquals("FALLBACK_CACHE", response.source());
        org.junit.jupiter.api.Assertions.assertTrue(response.stale());
        verify(providerB, never()).fetch(any());
    }

    @Test
    void allowsRecentlyReceivedQuoteAsStaleFallback() {
        PriceCache realCache = new PriceCache(null, null, Clock.fixed(NOW, ZoneOffset.UTC));
        PriceCache.CachedPrice recentlyStoredStaleQuote = new PriceCache.CachedPrice(
                quote(PriceProvider.PROVIDER_A, NOW.minusSeconds(3600)),
                NOW.minusSeconds(5)
        );

        org.junit.jupiter.api.Assertions.assertTrue(realCache.isFallbackUsable(recentlyStoredStaleQuote, NOW));
    }

    @Test
    void coalescesConcurrentMissesForTheSameRuleVersion() throws Exception {
        ProductRule rule = rule(PreferredProvider.AUTO);
        PriceQuote quote = quote(PriceProvider.PROVIDER_A, NOW);
        int concurrentRequests = 30;
        CountDownLatch allRuleReads = new CountDownLatch(concurrentRequests);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicReference<PriceCache.CachedPrice> cached = new AtomicReference<>();
        when(rules.findActiveBySku("ABC-123")).thenAnswer(invocation -> {
            allRuleReads.countDown();
            return rule;
        });
        when(cache.get("ABC-123", rule.getId(), rule.getVersion()))
                .thenAnswer(invocation -> Optional.ofNullable(cached.get()));
        lenient().when(cache.isFresh(any(PriceCache.CachedPrice.class), eq(NOW))).thenReturn(true);
        doAnswer(invocation -> {
            cached.set(new PriceCache.CachedPrice(quote, NOW));
            return null;
        }).when(cache).put("ABC-123", rule.getId(), rule.getVersion(), quote);
        when(providerA.fetch("ABC-123")).thenAnswer(invocation -> {
            providerStarted.countDown();
            if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("El test no liberó el proveedor a tiempo");
            }
            return quote;
        });
        ExecutorService callers = Executors.newFixedThreadPool(concurrentRequests);

        try {
            List<Future<PriceResponse>> results = new ArrayList<>(concurrentRequests);
            for (int request = 0; request < concurrentRequests; request++) {
                results.add(callers.submit(() -> service.lookup("ABC-123")));
            }

            assertTrue(providerStarted.await(5, TimeUnit.SECONDS));
            assertTrue(allRuleReads.await(5, TimeUnit.SECONDS));
            releaseProvider.countDown();

            for (Future<PriceResponse> result : results) {
                String source = result.get(5, TimeUnit.SECONDS).source();
                assertTrue(source.equals("PROVIDER_A") || source.equals("CACHE"));
            }
            verify(providerA, org.mockito.Mockito.times(1)).fetch("ABC-123");
        } finally {
            releaseProvider.countDown();
            callers.shutdownNow();
        }
    }

    private ProductRule rule(PreferredProvider preferredProvider) {
        return new ProductRule("ABC-123", "Cable", new BigDecimal("15.00"), preferredProvider, true);
    }

    private PriceQuote quote(PriceProvider provider, Instant timestamp) {
        return new PriceQuote("ABC-123", new BigDecimal("100.00"), "USD", provider, timestamp);
    }
}