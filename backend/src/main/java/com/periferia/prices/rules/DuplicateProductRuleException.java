package com.periferia.prices.rules;

public class DuplicateProductRuleException extends RuntimeException {
    public DuplicateProductRuleException(String sku) {
        super("Ya existe una regla para el SKU " + sku);
    }
}