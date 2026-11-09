package com.periferia.prices.rules;

import com.periferia.prices.pricing.PriceCache;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductRuleService {

    private final ProductRuleRepository repository;
    private final PriceCache priceCache;

    public ProductRuleService(ProductRuleRepository repository, PriceCache priceCache) {
        this.repository = repository;
        this.priceCache = priceCache;
    }

    @Transactional
    public ProductRuleResponse create(CreateProductRuleRequest request) {
        String sku = normalizeSku(request.sku());
        if (repository.findBySkuIgnoreCase(sku).isPresent()) {
            throw new DuplicateProductRuleException(sku);
        }

        ProductRule rule = new ProductRule(
                sku,
                request.name().trim(),
                request.marginPercentage(),
                request.preferredProvider(),
                request.active()
        );
        try {
            return ProductRuleResponse.from(repository.saveAndFlush(rule));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateProductRuleException(sku);
        }
    }

    @Transactional(readOnly = true)
    public List<ProductRuleResponse> list() {
        return repository.findAllByOrderBySkuAsc().stream()
                .map(ProductRuleResponse::from)
                .toList();
    }

    @Transactional
    public ProductRuleResponse update(String sku, UpdateProductRuleRequest request) {
        ProductRule rule = findBySku(sku);
        long oldVersion = rule.getVersion();
        rule.update(request);
        ProductRuleResponse response = ProductRuleResponse.from(repository.saveAndFlush(rule));
        priceCache.invalidate(rule.getSku(), rule.getId(), oldVersion);
        return response;
    }

    @Transactional
    public void delete(String sku) {
        ProductRule rule = findBySku(sku);
        long version = rule.getVersion();
        repository.delete(rule);
        repository.flush();
        priceCache.invalidate(rule.getSku(), rule.getId(), version);
    }

    @Transactional(readOnly = true)
    public ProductRule findActiveBySku(String sku) {
        ProductRule rule = findBySku(sku);
        if (!rule.isActive()) {
            throw new ProductRuleNotFoundException(sku);
        }
        return rule;
    }

    private ProductRule findBySku(String sku) {
        String normalizedSku = normalizeSku(sku);
        return repository.findBySkuIgnoreCase(normalizedSku)
                .orElseThrow(() -> new ProductRuleNotFoundException(normalizedSku));
    }

    private String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }
}