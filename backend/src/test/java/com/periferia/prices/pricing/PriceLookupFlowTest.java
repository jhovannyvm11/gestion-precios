package com.periferia.prices.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.periferia.prices.rules.PreferredProvider;
import com.periferia.prices.rules.CreateProductRuleRequest;
import com.periferia.prices.rules.ProductRule;
import com.periferia.prices.rules.ProductRuleRepository;
import com.periferia.prices.rules.ProductRuleService;
import com.periferia.prices.rules.UpdateProductRuleRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriceLookupFlowTest {

    private static final String SKU = "ABC-123";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T12:00:00Z"));
    private final ProductRuleRepository repository = mock(ProductRuleRepository.class);
    private final ProviderAClient providerA = mock(ProviderAClient.class);
    private final ProviderBClient providerB = mock(ProviderBClient.class);
    private final ProviderBRateLimiter rateLimiter = mock(ProviderBRateLimiter.class);
    private final InMemoryPriceCache cache = new InMemoryPriceCache(clock);

    private ProductRule rule;
    private ProductRuleService rules;
    private PriceLookupService prices;

    @BeforeEach
    void setUp() {
        rule = new ProductRule(SKU, "Cable", new BigDecimal("15.00"), PreferredProvider.AUTO, true);
        when(repository.findBySkuIgnoreCase(SKU)).thenReturn(Optional.of(rule));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(providerA.fetch(SKU)).thenAnswer(invocation -> freshQuoteFromA());

        rules = new ProductRuleService(repository, cache);
        prices = new PriceLookupService(rules, providerA, providerB, rateLimiter, cache, clock, Runnable::run);
    }

    @Test
    void servesRepeatedRequestFromCacheAndRecalculatesAfterRuleUpdate() {
        PriceResponse first = prices.lookup(SKU);
        PriceResponse second = prices.lookup(SKU);

        assertEquals("PROVIDER_A", first.source());
        assertEquals("CACHE", second.source());
        verify(providerA, times(1)).fetch(SKU);

        rules.update(" abc-123 ", new UpdateProductRuleRequest("Cable", new BigDecimal("20.00"), PreferredProvider.AUTO, true));
        PriceResponse afterUpdate = prices.lookup(SKU);

        assertEquals("PROVIDER_A", afterUpdate.source());
        assertEquals(new BigDecimal("120.00"), afterUpdate.finalPrice());
        verify(providerA, times(2)).fetch(SKU);
    }

    @Test
    void queriesProviderAgainOnceCacheTtlExpires() {
        prices.lookup(SKU);
        clock.advance(Duration.ofSeconds(59));
        assertEquals("CACHE", prices.lookup(SKU).source());

        clock.advance(Duration.ofSeconds(2));
        PriceResponse afterTtl = prices.lookup(SKU);

        assertEquals("PROVIDER_A", afterTtl.source());
        verify(providerA, times(2)).fetch(SKU);
    }

    @Test
    void normalizesSkuBeforeLookingUpAndCallingProviders() {
        PriceResponse response = prices.lookup(" abc-123 ");

        assertEquals(SKU, response.sku());
        verify(repository).findBySkuIgnoreCase(SKU);
        verify(providerA).fetch(SKU);
    }

    @Test
    void createsRulesWithTrimmedUppercaseSku() {
        when(repository.findBySkuIgnoreCase(SKU)).thenReturn(Optional.empty());

        var response = rules.create(new CreateProductRuleRequest(
                " abc-123 ",
                "Cable",
                new BigDecimal("15.00"),
                PreferredProvider.AUTO,
                true
        ));

        assertEquals(SKU, response.sku());
        verify(repository).findBySkuIgnoreCase(SKU);
    }

        @Test
        void deletesLegacyLowercaseSkuWhenRequestUsesUppercase() {
        ProductRule legacyRule = new ProductRule(
            "wec-321",
            "Producto legacy",
            new BigDecimal("23.00"),
            PreferredProvider.AUTO,
            true
        );
        PriceQuote cachedQuote = new PriceQuote(
            "wec-321",
            new BigDecimal("100.00"),
            "USD",
            PriceProvider.PROVIDER_A,
            clock.instant()
        );
        when(repository.findBySkuIgnoreCase("WEC-321")).thenReturn(Optional.of(legacyRule));
        cache.put("wec-321", legacyRule.getId(), legacyRule.getVersion(), cachedQuote);

        rules.delete("WEC-321");

        verify(repository).delete(legacyRule);
        org.junit.jupiter.api.Assertions.assertTrue(
            cache.get("wec-321", legacyRule.getId(), legacyRule.getVersion()).isEmpty()
        );
        }

    private PriceQuote freshQuoteFromA() {
        return new PriceQuote(SKU, new BigDecimal("100.00"), "USD", PriceProvider.PROVIDER_A, clock.instant());
    }

    private static final class InMemoryPriceCache extends PriceCache {

        private final Map<String, CachedPrice> entries = new HashMap<>();
        private final Clock clock;

        InMemoryPriceCache(Clock clock) {
            super(null, null, clock);
            this.clock = clock;
        }

        @Override
        public Optional<CachedPrice> get(String sku, UUID ruleId, long ruleVersion) {
            return Optional.ofNullable(entries.get(key(sku, ruleId, ruleVersion)));
        }

        @Override
        public void put(String sku, UUID ruleId, long ruleVersion, PriceQuote quote) {
            entries.put(key(sku, ruleId, ruleVersion), new CachedPrice(quote, clock.instant()));
        }

        @Override
        public void invalidate(String sku, UUID ruleId, long ruleVersion) {
            entries.remove(key(sku, ruleId, ruleVersion));
        }

        private String key(String sku, UUID ruleId, long ruleVersion) {
            return sku + ":" + ruleId + ":" + ruleVersion;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
