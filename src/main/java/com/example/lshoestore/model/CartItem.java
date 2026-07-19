package com.example.lshoestore.model;

import java.math.BigDecimal;

public class CartItem {
    private Product product;
    private int quantity;
    private String selectedSize;
    private int availableStock;

    public CartItem(Product product, int quantity, String selectedSize) {
        this(product, quantity, selectedSize,
                product == null ? 0 : product.getStockForSize(selectedSize));
    }

    public CartItem(Product product, int quantity, String selectedSize, int availableStock) {
        this.product = product;
        this.quantity = quantity;
        this.selectedSize = selectedSize;
        this.availableStock = Math.max(availableStock, 0);
    }

    public Product getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getSelectedSize() { return selectedSize; }
    public void setSelectedSize(String selectedSize) { this.selectedSize = selectedSize; }
    public int getAvailableStock() { return availableStock; }
    public void setAvailableStock(int availableStock) { this.availableStock = Math.max(availableStock, 0); }

    public BigDecimal getSubtotal() {
        if (product == null || product.getPrice() == null) return BigDecimal.ZERO;
        return product.getPrice().multiply(BigDecimal.valueOf(quantity));
    }
}
