package com.periferia.prices.rules;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/product-rules")
public class ProductRuleController {

    private final ProductRuleService service;

    public ProductRuleController(ProductRuleService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProductRuleResponse> create(@Valid @RequestBody CreateProductRuleRequest request) {
        ProductRuleResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/product-rules/" + created.sku())).body(created);
    }

    @GetMapping
    public List<ProductRuleResponse> list() {
        return service.list();
    }

    @PutMapping("/{sku}")
    public ProductRuleResponse update(
            @PathVariable String sku,
            @Valid @RequestBody UpdateProductRuleRequest request
    ) {
        return service.update(sku, request);
    }

    @DeleteMapping("/{sku}")
    public ResponseEntity<Void> delete(@PathVariable String sku) {
        service.delete(sku);
        return ResponseEntity.noContent().build();
    }
}