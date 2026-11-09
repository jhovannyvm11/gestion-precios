package com.periferia.prices.rules;

public class ProductRuleNotFoundException extends RuntimeException {
    public ProductRuleNotFoundException(String sku) {
        super("No existe una regla activa para el SKU " + sku);
    }
}