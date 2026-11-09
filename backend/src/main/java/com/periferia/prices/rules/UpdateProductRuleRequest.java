package com.periferia.prices.rules;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record UpdateProductRuleRequest(
        @NotBlank @Size(max = 160)
        String name,
        @NotNull @DecimalMin("0.00") @DecimalMax("1000.00")
        BigDecimal marginPercentage,
        @NotNull
        PreferredProvider preferredProvider,
        boolean active
) {
}