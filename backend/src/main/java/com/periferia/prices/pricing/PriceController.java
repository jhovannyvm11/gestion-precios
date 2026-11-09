package com.periferia.prices.pricing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PriceController {

    private final PriceLookupService service;

    public PriceController(PriceLookupService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/prices")
    public PriceResponse price(@RequestParam String sku) {
        return service.lookup(sku);
    }
}