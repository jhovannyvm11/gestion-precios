package com.periferia.prices.rules;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductRuleResponse(
        UUID id,
        String sku,
        String name,
        BigDecimal marginPercentage,
        PreferredProvider preferredProvider,
        boolean active,
        Instant updatedAt
) {
    public static ProductRuleResponse from(ProductRule rule) {
        return new ProductRuleResponse(
                rule.getId(),
                rule.getSku(),
                rule.getName(),
                rule.getMarginPercentage(),
                rule.getPreferredProvider(),
                rule.isActive(),
                rule.getUpdatedAt()
        );
    }
}