package com.example.lshoestore.model;

import jakarta.persistence.*;

@Entity
@Table(name = "saved_cart_items", indexes = {
        @Index(name = "idx_saved_cart_user", columnList = "user_id"),
        @Index(name = "idx_saved_cart_product", columnList = "product_id")
})
public class SavedCartItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    // Để nullable ở mức JPA nhằm nâng cấp an toàn các database cũ; migration sẽ điền dữ liệu và đặt NOT NULL.
    @Column(name = "selected_size", length = 160)
    private String selectedSize;

    public SavedCartItem() {}

    public SavedCartItem(User user, Product product, int quantity, String selectedSize) {
        this.user = user;
        this.product = product;
        this.quantity = quantity;
        this.selectedSize = selectedSize;
    }

    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getSelectedSize() { return selectedSize; }
    public void setSelectedSize(String selectedSize) { this.selectedSize = selectedSize; }
}
