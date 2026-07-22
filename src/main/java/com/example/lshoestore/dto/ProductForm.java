package com.example.lshoestore.dto;

import com.example.lshoestore.model.Product;
import com.example.lshoestore.model.ProductVariant;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

public class ProductForm {
    private Long id;
    private Long version;

    @NotBlank(message = "Tên sản phẩm không được để trống")
    @Size(max = 160, message = "Tên sản phẩm tối đa 160 ký tự")
    private String name;

    @NotBlank(message = "Tên hãng không được để trống")
    @Size(max = 100, message = "Tên hãng tối đa 100 ký tự")
    private String brand;

    @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự")
    private String description;

    @NotNull(message = "Giá bán không được để trống")
    @DecimalMin(value = "1000", message = "Giá bán phải từ 1.000đ")
    @DecimalMax(value = "9999999999", message = "Giá bán quá lớn")
    private BigDecimal price;

    @DecimalMin(value = "0", message = "Giá gốc không được âm")
    @DecimalMax(value = "9999999999", message = "Giá gốc quá lớn")
    private BigDecimal oldPrice;

    @Size(max = 1000, message = "Liên kết ảnh tối đa 1000 ký tự")
    private String imageUrl;

    @NotBlank(message = "Vui lòng nhập tồn kho theo từng kích cỡ")
    @Size(max = 2000, message = "Danh sách tồn kho kích cỡ tối đa 2000 ký tự")
    private String variantStockText;

    /**
     * Read-only summary of stock retained on disabled variants. This stock can be
     * restored by cancelled historical orders and must not be silently overwritten
     * when an administrator enables the same size again.
     */
    private String disabledVariantStockText;

    private boolean active = true;

    @NotNull(message = "Vui lòng chọn danh mục")
    private Long categoryId;

    public static ProductForm from(Product product) {
        return from(product, product.getVariants());
    }

    public static ProductForm from(Product product, List<ProductVariant> productVariants) {
        ProductForm form = new ProductForm();
        form.id = product.getId();
        form.version = product.getVersion();
        form.name = product.getName();
        form.brand = product.getBrand();
        form.description = product.getDescription();
        form.price = product.getPrice();
        form.oldPrice = product.getOldPrice();
        form.imageUrl = product.getImageUrl();
        List<ProductVariant> safeVariants = productVariants == null ? List.of() : productVariants;
        form.variantStockText = safeVariants.stream()
                .filter(ProductVariant::isEnabled)
                .sorted(Comparator.comparing(ProductVariant::getSize,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(variant -> variant.getSize() + ": " + variant.getStock())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        form.disabledVariantStockText = safeVariants.stream()
                .filter(variant -> !variant.isEnabled() && variant.getStock() > 0)
                .sorted(Comparator.comparing(ProductVariant::getSize,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(variant -> variant.getSize() + ": " + variant.getStock())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        form.active = product.isActive();
        form.categoryId = product.getCategory() == null ? null : product.getCategory().getId();
        return form;
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
    public String getVariantStockText() { return variantStockText; }
    public void setVariantStockText(String variantStockText) { this.variantStockText = variantStockText; }
    public String getDisabledVariantStockText() { return disabledVariantStockText; }
    public void setDisabledVariantStockText(String disabledVariantStockText) {
        this.disabledVariantStockText = disabledVariantStockText;
    }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
}
