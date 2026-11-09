package com.periferia.prices.pricing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

public record PriceQuote(
        String sku,
        BigDecimal providerPrice,
        String currency,
        PriceProvider provider,
        Instant quotedAt
) {
    public boolean isStale(Instant now, Duration freshnessWindow) {
        return quotedAt.isBefore(now.minus(freshnessWindow));
    }
}