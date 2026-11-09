package com.periferia.prices.rules;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateProductRuleRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        String sku,
        @NotBlank @Size(max = 160)
        String name,
        @NotNull @DecimalMin("0.00") @DecimalMax("1000.00")
        BigDecimal marginPercentage,
        @NotNull
        PreferredProvider preferredProvider,
        boolean active
) {
}