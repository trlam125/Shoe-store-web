package com.example.lshoestore.service;

import com.example.lshoestore.dto.ProductForm;
import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.exception.ResourceNotFoundException;
import com.example.lshoestore.model.Category;
import com.example.lshoestore.model.Product;
import com.example.lshoestore.repository.CategoryRepository;
import com.example.lshoestore.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

@Service
public class ProductAdminService {
    private final ProductRepository products;
    private final CategoryRepository categories;

    public ProductAdminService(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    @Transactional
    public Product save(ProductForm form) {
        Category category = categories.findById(form.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục"));

        Product product;
        if (form.getId() == null) {
            product = new Product();
            product.setActive(true);
        } else {
            product = products.findByIdWithLock(form.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
            if (!Objects.equals(product.getVersion(), form.getVersion())) {
                throw new BusinessException(
                        "Sản phẩm đã thay đổi bởi một thao tác khác. Hãy tải lại trang và thử lại.",
                        "stale_product");
            }
            product.setActive(form.isActive());
        }

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
        product.setSizeText(cleanNullable(form.getSizeText()));
        product.setStock(form.getStock());
        product.setCategory(category);

        product.setImageUrl(cleanNullable(form.getImageUrl()));
        return products.saveAndFlush(product);
    }

    @Transactional
    public void toggleActive(Long id) {
        Product product = products.findByIdWithLock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
        product.setActive(!product.isActive());
    }

    @Transactional(readOnly = true)
    public ProductForm getForm(Long id) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
        return ProductForm.from(product);
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }
    private String cleanNullable(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
    private BigDecimal zeroToNull(BigDecimal value) {
        return value != null && value.signum() == 0 ? null : value;
    }
}
