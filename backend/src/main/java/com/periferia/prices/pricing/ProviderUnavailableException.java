package com.periferia.prices.pricing;

public class ProviderUnavailableException extends RuntimeException {
    public ProviderUnavailableException(String sku) {
        super("No hay cotización disponible para el SKU " + sku);
    }
}