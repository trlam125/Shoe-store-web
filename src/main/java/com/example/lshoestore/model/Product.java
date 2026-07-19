package com.example.lshoestore.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(indexes = {
        @Index(name = "idx_product_active", columnList = "active"),
        @Index(name = "idx_product_category", columnList = "category_id")
})
public class Product {
    public static final String DEFAULT_SIZE = "Không áp dụng";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    @Column(precision = 15, scale = 2)
    private BigDecimal oldPrice;

    @Column(length = 1000)
    private String imageUrl;

    /**
     * Legacy inventory summary kept for existing SQL reports and the AI service.
     * The source of truth is {@link #variants}.
     */
    @Column(length = 160)
    private String sizeText;

    /** Total enabled variant stock, retained as a query-friendly summary column. */
    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL,
            orphanRemoval = false, fetch = FetchType.LAZY)
    private List<ProductVariant> variants = new ArrayList<>();

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    @Transient
    public List<ProductVariant> getEnabledVariants() {
        if (variants == null || variants.isEmpty()) return List.of();
        return variants.stream()
                .filter(ProductVariant::isEnabled)
                .sorted(Comparator.comparing(ProductVariant::getSize,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    @Transient
    public List<String> getAvailableSizes() {
        List<String> variantSizes = getEnabledVariants().stream()
                .map(ProductVariant::getSize)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (!variants.isEmpty()) return variantSizes;

        if (sizeText == null || sizeText.isBlank()) return List.of(DEFAULT_SIZE);
        Set<String> unique = new LinkedHashSet<>();
        for (String token : sizeText.split("[,;/|\\n]+")) {
            String value = token == null ? "" : token.trim();
            if (!value.isBlank()) unique.add(value);
        }
        return unique.isEmpty() ? List.of(DEFAULT_SIZE) : new ArrayList<>(unique);
    }

    @Transient
    public int getStockForSize(String requestedSize) {
        String cleaned = cleanSize(requestedSize);
        if (!variants.isEmpty()) {
            return variants.stream()
                    .filter(ProductVariant::isEnabled)
                    .filter(variant -> cleanSize(variant.getSize()).equalsIgnoreCase(cleaned))
                    .mapToInt(ProductVariant::getStock)
                    .findFirst()
                    .orElse(0);
        }
        return getAvailableSizes().stream().anyMatch(size -> size.equalsIgnoreCase(cleaned))
                ? Math.max(stock, 0) : 0;
    }

    @Transient
    public String getFirstInStockSize() {
        return getEnabledVariants().stream()
                .filter(variant -> variant.getStock() > 0)
                .map(ProductVariant::getSize)
                .findFirst()
                .orElseGet(() -> getAvailableSizes().stream()
                        .filter(size -> getStockForSize(size) > 0)
                        .findFirst().orElse(""));
    }

    @Transient
    public String getInventorySummary() {
        if (!variants.isEmpty()) {
            return getEnabledVariants().stream()
                    .map(variant -> variant.getSize() + ": " + variant.getStock())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("Chưa cấu hình kích cỡ");
        }
        return getAvailableSizes().stream()
                .map(size -> size + ": " + stock)
                .reduce((left, right) -> left + ", " + right)
                .orElse("Chưa cấu hình kích cỡ");
    }

    public void addVariant(ProductVariant variant) {
        if (variant == null) return;
        variant.setProduct(this);
        variants.add(variant);
    }

    public void syncInventorySummary() {
        List<ProductVariant> enabled = getEnabledVariants();
        if (variants.isEmpty()) return;
        long total = enabled.stream().mapToLong(ProductVariant::getStock).sum();
        if (total > Integer.MAX_VALUE) {
            throw new IllegalStateException("Tổng tồn kho vượt giới hạn hệ thống");
        }
        stock = (int) total;
        sizeText = enabled.stream()
                .map(ProductVariant::getSize)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
    }

    /**
     * Marks the parent product as changed when its variant inventory is edited.
     * This forces optimistic-lock version advancement even if the aggregate stock
     * and all other scalar product fields end up with their previous values.
     */
    public void markInventoryChanged() {
        LocalDateTime now = LocalDateTime.now();
        updatedAt = updatedAt == null || now.isAfter(updatedAt)
                ? now
                : updatedAt.plusNanos(1);
    }

    private String cleanSize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getOldPrice() { return oldPrice; }
    public void setOldPrice(BigDecimal oldPrice) { this.oldPrice = oldPrice; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getSizeText() { return sizeText; }
    public void setSizeText(String sizeText) { this.sizeText = sizeText; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public List<ProductVariant> getVariants() { return variants; }
    public void setVariants(List<ProductVariant> variants) {
        this.variants = variants == null ? new ArrayList<>() : variants;
        this.variants.forEach(variant -> variant.setProduct(this));
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
