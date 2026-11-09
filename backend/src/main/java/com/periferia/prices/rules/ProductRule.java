package com.periferia.prices.rules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_rules")
public class ProductRule {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal marginPercentage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PreferredProvider preferredProvider;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ProductRule() {
    }

    public ProductRule(
            String sku,
            String name,
            BigDecimal marginPercentage,
            PreferredProvider preferredProvider,
            boolean active
    ) {
        this.sku = sku;
        this.name = name;
        this.marginPercentage = marginPercentage;
        this.preferredProvider = preferredProvider;
        this.active = active;
    }

    @PrePersist
    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getMarginPercentage() {
        return marginPercentage;
    }

    public PreferredProvider getPreferredProvider() {
        return preferredProvider;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void update(UpdateProductRuleRequest request) {
        name = request.name().trim();
        marginPercentage = request.marginPercentage();
        preferredProvider = request.preferredProvider();
        active = request.active();
    }
}