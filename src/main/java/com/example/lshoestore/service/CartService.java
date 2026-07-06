package com.example.lshoestore.service;

import com.example.lshoestore.model.*;
import com.example.lshoestore.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.SessionScope;

import java.math.BigDecimal;
import java.util.*;

@Service
@SessionScope
public class CartService {
    private final ProductRepository productRepository;
    private final Map<Long, CartItem> items = new LinkedHashMap<>();

    public CartService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public void add(Long productId) {
        Product p = productRepository.findById(productId).orElseThrow();
        CartItem item = items.get(productId);
        if (item == null) items.put(productId, new CartItem(p, 1));
        else item.setQuantity(item.getQuantity() + 1);
    }

    public void update(Long productId, int quantity) {
        if (quantity <= 0) items.remove(productId);
        else if (items.containsKey(productId)) items.get(productId).setQuantity(quantity);
    }

    public void remove(Long productId) {
        items.remove(productId);
    }

    public void clear() {
        items.clear();
    }

    public Collection<CartItem> getItems() {
        return items.values();
    }

    public int count() {
        return items.values().stream().mapToInt(CartItem::getQuantity).sum();
    }

    public BigDecimal total() {
        return items.values().stream().map(i -> i.getProduct().getPrice().multiply(BigDecimal.valueOf(i.getQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
