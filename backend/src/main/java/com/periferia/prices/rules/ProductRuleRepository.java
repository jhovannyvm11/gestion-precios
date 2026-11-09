package com.periferia.prices.rules;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRuleRepository extends JpaRepository<ProductRule, UUID> {
    Optional<ProductRule> findBySkuIgnoreCase(String sku);

    List<ProductRule> findAllByOrderBySkuAsc();
}