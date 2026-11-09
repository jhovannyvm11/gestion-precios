package com.periferia.prices.pricing;

import com.periferia.prices.rules.PreferredProvider;
import com.periferia.prices.rules.ProductRule;
import com.periferia.prices.rules.ProductRuleService;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class PriceLookupService {

    private final ProductRuleService rules;
    private final ProviderAClient providerA;
    private final ProviderBClient providerB;
    private final ProviderBRateLimiter rateLimiter;
    private final PriceCache cache;
    private final Clock clock;
    private final Executor refreshExecutor;
    private final ConcurrentHashMap<RuleKey, CompletableFuture<PriceResponse>> inFlight = new ConcurrentHashMap<>();

    public PriceLookupService(
            ProductRuleService rules,
            ProviderAClient providerA,
            ProviderBClient providerB,
            ProviderBRateLimiter rateLimiter,
            PriceCache cache,
            Clock clock,
            @Qualifier("priceRefreshExecutor") Executor refreshExecutor
    ) {
        this.rules = rules;
        this.providerA = providerA;
        this.providerB = providerB;
        this.rateLimiter = rateLimiter;
        this.cache = cache;
        this.clock = clock;
        this.refreshExecutor = refreshExecutor;
    }

    public PriceResponse lookup(String sku) {
        long startedAt = System.nanoTime();
        ProductRule rule = rules.findActiveBySku(sku);
        RuleKey key = new RuleKey(rule.getSku(), rule.getId(), rule.getVersion());
        CompletableFuture<PriceResponse> current = new CompletableFuture<>();
        CompletableFuture<PriceResponse> existing = inFlight.putIfAbsent(key, current);
        if (existing != null) {
            return await(existing, startedAt);
        }

        try {
            PriceResponse response = lookupUncoalesced(rule, startedAt);
            current.complete(response);
            return response;
        } catch (RuntimeException exception) {
            current.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, current);
        }
    }

    private PriceResponse lookupUncoalesced(ProductRule rule, long startedAt) {
        String sku = rule.getSku();
        Instant now = clock.instant();
        Optional<PriceCache.CachedPrice> cached = cache.get(sku, rule.getId(), rule.getVersion());

        if (cached.filter(entry -> cache.isFresh(entry, now)).isPresent()) {
            return response(rule, cached.get().quote(), "CACHE", false, startedAt);
        }

        if (rule.getPreferredProvider() == PreferredProvider.PROVIDER_B) {
            return fetchBOrFallback(rule, cached, startedAt);
        }

        try {
            PriceQuote quote = providerA.fetch(sku);
            cache.put(sku, rule.getId(), rule.getVersion(), quote);
            if (quote.isStale(clock.instant(), Duration.ofMinutes(15))) {
                refreshFromBInBackground(rule);
            }
            return response(rule, quote, "PROVIDER_A", quote.isStale(clock.instant(), Duration.ofMinutes(15)), startedAt);
        } catch (RuntimeException providerFailure) {
            return fetchBOrFallback(rule, cached, startedAt);
        }
    }

    private PriceResponse fetchBOrFallback(ProductRule rule, Optional<PriceCache.CachedPrice> cached, long startedAt) {
        try {
            PriceQuote quote = fetchB(rule.getSku());
            cache.put(rule.getSku(), rule.getId(), rule.getVersion(), quote);
            return response(
                    rule,
                    quote,
                    "PROVIDER_B",
                    quote.isStale(clock.instant(), Duration.ofMinutes(15)),
                    startedAt
            );
        } catch (RuntimeException providerFailure) {
            return fallback(rule, cached, startedAt).orElseThrow(() -> new ProviderUnavailableException(rule.getSku()));
        }
    }

    private PriceQuote fetchB(String sku) {
        if (!rateLimiter.tryAcquire()) {
            throw new ProviderUnavailableException(sku);
        }
        return providerB.fetch(sku);
    }

    private Optional<PriceResponse> fallback(
            ProductRule rule,
            Optional<PriceCache.CachedPrice> cached,
            long startedAt
    ) {
        return cached
                .filter(entry -> cache.isFallbackUsable(entry, clock.instant()))
                .map(entry -> response(rule, entry.quote(), "FALLBACK_CACHE", true, startedAt));
    }

    private void refreshFromBInBackground(ProductRule rule) {
        refreshExecutor.execute(() -> {
            try {
                PriceQuote quote = fetchB(rule.getSku());
                cache.put(rule.getSku(), rule.getId(), rule.getVersion(), quote);
            } catch (RuntimeException ignored) {
                // A stale quote remains available until its retention period expires.
            }
        });
    }

    private PriceResponse response(
            ProductRule rule,
            PriceQuote quote,
            String source,
            boolean stale,
            long startedAt
    ) {
        var finalPrice = quote.providerPrice()
                .multiply(rule.getMarginPercentage().movePointLeft(2).add(java.math.BigDecimal.ONE))
                .setScale(2, RoundingMode.HALF_UP);
        return new PriceResponse(
                rule.getSku(),
                rule.getName(),
                quote.providerPrice(),
                rule.getMarginPercentage(),
                finalPrice,
                quote.currency(),
                source,
                stale,
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
        );
    }

    private PriceResponse await(CompletableFuture<PriceResponse> future, long startedAt) {
        try {
            PriceResponse shared = future.join();
            return new PriceResponse(
                    shared.sku(),
                    shared.productName(),
                    shared.providerPrice(),
                    shared.marginPercentage(),
                    shared.finalPrice(),
                    shared.currency(),
                    shared.source(),
                    shared.stale(),
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
            );
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private record RuleKey(String sku, UUID ruleId, long version) {
    }
}