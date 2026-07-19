package com.example.lshoestore.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_variant", indexes = {
        @Index(name = "idx_product_variant_product", columnList = "product_id"),
        @Index(name = "idx_product_variant_enabled_stock", columnList = "enabled,stock")
})
public class ProductVariant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 160)
    private String size;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ProductVariant() {
    }

    public ProductVariant(Product product, String size, int stock) {
        this.product = product;
        this.size = size;
        this.stock = stock;
        this.enabled = true;
    }

    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
