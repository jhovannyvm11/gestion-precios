package com.periferia.prices.pricing;

import java.math.BigDecimal;

public record PriceResponse(
        String sku,
        String productName,
        BigDecimal providerPrice,
        BigDecimal marginPercentage,
        BigDecimal finalPrice,
        String currency,
        String source,
        boolean stale,
        long responseTimeMs
) {
}