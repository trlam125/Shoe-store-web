package com.example.lshoestore.service;

import com.example.lshoestore.dto.ProductForm;
import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.exception.ResourceNotFoundException;
import com.example.lshoestore.model.Category;
import com.example.lshoestore.model.Product;
import com.example.lshoestore.model.ProductVariant;
import com.example.lshoestore.repository.CategoryRepository;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.repository.ProductVariantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class ProductAdminService {
    private static final int MAX_VARIANT_STOCK = 1_000_000;
    private static final int MAX_TOTAL_STOCK = 1_000_000;

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;

    public ProductAdminService(ProductRepository products,
                               ProductVariantRepository variants,
                               CategoryRepository categories) {
        this.products = products;
        this.variants = variants;
        this.categories = categories;
    }

    @Transactional
    public Product save(ProductForm form) {
        Category category = categories.findById(form.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục"));
        LinkedHashMap<String, VariantInput> requestedVariants = parseVariantStock(form.getVariantStockText());

        Product product;
        List<ProductVariant> existingVariants;
        if (form.getId() == null) {
            product = new Product();
            existingVariants = List.of();
        } else {
            product = products.findByIdWithLock(form.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
            if (!Objects.equals(product.getVersion(), form.getVersion())) {
                throw new BusinessException(
                        "Sản phẩm đã thay đổi bởi một thao tác khác. Hãy tải lại trang và thử lại.",
                        "stale_product");
            }
            existingVariants = variants.findAllByProductIdWithLock(product.getId());
        }

        product.setActive(form.isActive());

        BigDecimal normalizedOldPrice = zeroToNull(form.getOldPrice());
        if (normalizedOldPrice != null && form.getPrice() != null
                && normalizedOldPrice.compareTo(form.getPrice()) < 0) {
            throw new BusinessException("Giá gốc không được nhỏ hơn giá bán.", "invalid_price");
        }

        product.setName(clean(form.getName()));
        product.setBrand(clean(form.getBrand()));
        product.setDescription(cleanNullable(form.getDescription()));
        product.setPrice(form.getPrice());
        product.setOldPrice(normalizedOldPrice);
        product.setCategory(category);
        product.setImageUrl(normalizeImageUrl(form.getImageUrl()));

        Map<String, ProductVariant> existingBySize = new LinkedHashMap<>();
        for (ProductVariant variant : existingVariants) {
            existingBySize.put(normalizeSizeKey(variant.getSize()), variant);
            // Keep known sizes instead of deleting them so historical order cancellations
            // can still restore the exact size. A currently enabled size omitted from the
            // form is being removed now, so disable it and clear its sellable stock. A size
            // that was already disabled may contain stock restored from an old cancelled
            // order; preserve that hidden stock instead of erasing it on unrelated edits.
            if (variant.isEnabled()) {
                variant.setEnabled(false);
                variant.setStock(0);
            }
        }

        for (VariantInput input : requestedVariants.values()) {
            ProductVariant variant = existingBySize.get(normalizeSizeKey(input.size()));
            if (variant == null) {
                variant = new ProductVariant(product, input.size(), input.stock());
                product.addVariant(variant);
            } else {
                int stockToSave = input.stock();
                // A disabled size can hold stock restored from cancelled historical orders.
                // When the administrator enables that size again, treat the entered value as
                // additional stock so the retained quantity is never silently overwritten.
                if (!variant.isEnabled() && variant.getStock() > 0) {
                    long mergedStock = (long) variant.getStock() + input.stock();
                    if (mergedStock > MAX_VARIANT_STOCK) {
                        throw new BusinessException(
                                "Tồn kho của size " + input.size() + " sau khi cộng phần đang giữ "
                                        + "không được vượt quá " + MAX_VARIANT_STOCK + ".",
                                "invalid_variant_stock");
                    }
                    stockToSave = (int) mergedStock;
                }
                variant.setSize(input.size());
                variant.setStock(stockToSave);
                variant.setEnabled(true);
            }
        }

        long enabledTotal = product.getVariants().stream()
                .filter(ProductVariant::isEnabled)
                .mapToLong(ProductVariant::getStock)
                .sum();
        if (enabledTotal > MAX_TOTAL_STOCK) {
            throw new BusinessException(
                    "Tổng tồn kho sản phẩm sau khi khôi phục size đã tắt không được vượt quá "
                            + MAX_TOTAL_STOCK + ".",
                    "stock_limit");
        }
        product.syncInventorySummary();
        // Always touch the parent row when variants are saved. This guarantees that the
        // Product @Version value changes even when stock is only redistributed between sizes
        // and the total stock remains the same.
        product.markInventoryChanged();
        return products.saveAndFlush(product);
    }

    @Transactional
    public void toggleActive(Long id) {
        Product product = products.findByIdWithLock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
        product.setActive(!product.isActive());
    }

    /**
     * Safely removes a product from the storefront without physically deleting rows.
     *
     * Checkout locks a user's saved-cart rows before locking products. A physical delete
     * would need to delete those cart rows after locking the product, creating the opposite
     * lock order and a possible PostgreSQL deadlock. Archiving only updates the product row,
     * preserves order history and allows the administrator to restore the product later.
     *
     * @return true when the product changed from active to archived; false if it was already archived
     */
    @Transactional
    public boolean archive(Long id) {
        Product product = products.findByIdWithLock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
        if (!product.isActive()) return false;
        product.setActive(false);
        return true;
    }

    @Transactional(readOnly = true)
    public ProductForm getForm(Long id) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
        List<ProductVariant> productVariants = variants.findAllByProductId(product.getId());
        return ProductForm.from(product, productVariants);
    }

    static LinkedHashMap<String, VariantInput> parseVariantStock(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("Vui lòng nhập ít nhất một kích cỡ và tồn kho.", "variant_required");
        }

        LinkedHashMap<String, VariantInput> result = new LinkedHashMap<>();
        long total = 0;
        for (String token : raw.split("[,;\\n\\r]+")) {
            String entry = token == null ? "" : token.trim();
            if (entry.isBlank()) continue;
            int separator = entry.lastIndexOf(':');
            if (separator < 0) separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator >= entry.length() - 1) {
                throw new BusinessException(
                        "Sai định dạng tồn kho \"" + entry + "\". Hãy nhập theo mẫu 39: 5.",
                        "invalid_variant_format");
            }

            String size = entry.substring(0, separator).trim();
            String stockText = entry.substring(separator + 1).trim();
            if (size.isBlank() || size.length() > 160) {
                throw new BusinessException("Kích cỡ không hợp lệ hoặc vượt quá 160 ký tự.",
                        "invalid_variant_size");
            }

            int stock;
            try {
                stock = Integer.parseInt(stockText);
            } catch (NumberFormatException exception) {
                throw new BusinessException("Tồn kho của size " + size + " phải là số nguyên.",
                        "invalid_variant_stock");
            }
            if (stock < 0 || stock > MAX_VARIANT_STOCK) {
                throw new BusinessException(
                        "Tồn kho của size " + size + " phải từ 0 đến " + MAX_VARIANT_STOCK + ".",
                        "invalid_variant_stock");
            }

            String key = normalizeSizeKey(size);
            if (result.containsKey(key)) {
                throw new BusinessException("Kích cỡ " + size + " bị nhập trùng.",
                        "duplicate_variant_size");
            }
            result.put(key, new VariantInput(size, stock));
            total += stock;
            if (total > MAX_TOTAL_STOCK) {
                throw new BusinessException("Tổng tồn kho sản phẩm không được vượt quá "
                        + MAX_TOTAL_STOCK + ".", "stock_limit");
            }
        }
        if (result.isEmpty()) {
            throw new BusinessException("Vui lòng nhập ít nhất một kích cỡ và tồn kho.", "variant_required");
        }
        return result;
    }

    private String normalizeImageUrl(String value) {
        String normalized = cleanNullable(value);
        if (normalized == null) return null;

        // Application-relative static resources are valid, for example
        // /images/products/catalog-nike.png. Protocol-relative URLs are rejected.
        if (normalized.startsWith("/") && !normalized.startsWith("//")) {
            return normalized;
        }

        try {
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank()) {
                return normalized;
            }
        } catch (URISyntaxException ignored) {
            // Fall through to the domain-specific validation error below.
        }

        throw new BusinessException(
                "Link ảnh phải là đường dẫn nội bộ bắt đầu bằng / hoặc URL http/https hợp lệ.",
                "invalid_image_url");
    }

    private static String normalizeSizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }
    private String cleanNullable(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
    private BigDecimal zeroToNull(BigDecimal value) {
        return value != null && value.signum() == 0 ? null : value;
    }

    record VariantInput(String size, int stock) {}
}
