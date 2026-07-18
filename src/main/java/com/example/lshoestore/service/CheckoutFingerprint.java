package com.example.lshoestore.service;

import com.example.lshoestore.model.CartItem;
import com.example.lshoestore.model.Product;
import com.example.lshoestore.model.SavedCartItem;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Creates a stable digest of the exact cart state shown on the checkout page.
 * The digest is not a secret; it is used to detect cart or product changes
 * between reviewing checkout and submitting the order.
 */
public final class CheckoutFingerprint {
    private CheckoutFingerprint() {}

    public static String fromCartItems(List<CartItem> items) {
        StringBuilder canonical = new StringBuilder();
        items.stream()
                .sorted(Comparator.comparing((CartItem item) -> item.getProduct().getId())
                        .thenComparing(CartItem::getSelectedSize,
                                Comparator.nullsFirst(String::compareToIgnoreCase)))
                .forEach(item -> appendLine(
                        canonical,
                        item.getProduct(),
                        item.getSelectedSize(),
                        item.getQuantity()));
        return sha256(canonical.toString());
    }

    public static String fromSavedCartItems(List<SavedCartItem> rows,
                                            Map<Long, Product> lockedProducts) {
        StringBuilder canonical = new StringBuilder();
        rows.stream()
                .sorted(Comparator.comparing((SavedCartItem row) -> row.getProduct().getId())
                        .thenComparing(SavedCartItem::getSelectedSize,
                                Comparator.nullsFirst(String::compareToIgnoreCase))
                        .thenComparing(SavedCartItem::getId))
                .forEach(row -> appendLine(
                        canonical,
                        lockedProducts.get(row.getProduct().getId()),
                        row.getSelectedSize(),
                        row.getQuantity()));
        return sha256(canonical.toString());
    }

    private static void appendLine(StringBuilder target,
                                   Product product,
                                   String selectedSize,
                                   int quantity) {
        if (product == null || product.getId() == null) {
            throw new IllegalArgumentException("Cart contains an invalid product");
        }
        appendField(target, product.getId());
        appendField(target, product.getVersion());
        appendField(target, product.getName());
        appendField(target, product.getBrand());
        appendField(target, product.getImageUrl());
        appendField(target, normalizeMoney(product.getPrice()));
        appendField(target, product.isActive());
        appendField(target, product.getStock());
        appendField(target, selectedSize == null ? "" : selectedSize.trim());
        appendField(target, quantity);
        target.append('\n');
    }

    private static void appendField(StringBuilder target, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('|');
    }

    private static String normalizeMoney(BigDecimal value) {
        if (value == null) return "";
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
