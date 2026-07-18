package com.example.lshoestore.service;

import com.example.lshoestore.dto.CheckoutForm;
import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.exception.ResourceNotFoundException;
import com.example.lshoestore.model.Order;
import com.example.lshoestore.model.OrderItem;
import com.example.lshoestore.model.OrderStatus;
import com.example.lshoestore.model.Product;
import com.example.lshoestore.model.SavedCartItem;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.OrderRepository;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.repository.SavedCartItemRepository;
import com.example.lshoestore.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {
    private static final BigDecimal MAX_ORDER_TOTAL = new BigDecimal("9999999999999.99");
    private final OrderRepository orders;
    private final ProductRepository products;
    private final SavedCartItemRepository savedCartItems;
    private final UserRepository users;

    public OrderService(OrderRepository orders,
                        ProductRepository products,
                        SavedCartItemRepository savedCartItems,
                        UserRepository users) {
        this.orders = orders;
        this.products = products;
        this.savedCartItems = savedCartItems;
        this.users = users;
    }

    @Transactional
    public Order placeOrder(User user, CheckoutForm form, String expectedCartFingerprint) {
        User lockedUser = users.findByIdWithLock(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản người dùng"));

        if (orders.existsByCheckoutToken(form.getCheckoutToken())) {
            throw new BusinessException("Đơn hàng này đã được tạo trước đó.", "duplicate_checkout");
        }

        List<SavedCartItem> cartRows = new ArrayList<>(savedCartItems.findByUserWithLock(lockedUser));
        if (cartRows.isEmpty()) {
            throw new BusinessException("Giỏ hàng của bạn đang trống.", "empty_cart");
        }
        cartRows.sort(Comparator.comparing((SavedCartItem row) -> row.getProduct().getId())
                .thenComparing(SavedCartItem::getSelectedSize,
                        Comparator.nullsFirst(String::compareToIgnoreCase))
                .thenComparing(SavedCartItem::getId));

        Map<Long, Product> lockedProducts = lockProductsInStableOrder(cartRows);
        Map<Long, Long> quantityByProduct = new LinkedHashMap<>();
        for (SavedCartItem row : cartRows) {
            Product product = lockedProducts.get(row.getProduct().getId());
            if (!product.isActive()) {
                throw new BusinessException("Sản phẩm " + product.getName() + " hiện không còn bán.", "inactive");
            }
            if (row.getQuantity() <= 0) {
                throw new BusinessException("Số lượng sản phẩm không hợp lệ.", "quantity");
            }
            String selectedSize = normalizeOrderSize(product, row.getSelectedSize());
            row.setSelectedSize(selectedSize);
            quantityByProduct.merge(product.getId(), (long) row.getQuantity(), Long::sum);
        }
        String actualCartFingerprint = CheckoutFingerprint.fromSavedCartItems(cartRows, lockedProducts);
        if (expectedCartFingerprint == null || expectedCartFingerprint.isBlank()
                || !expectedCartFingerprint.equals(actualCartFingerprint)) {
            throw new BusinessException(
                    "Giỏ hàng hoặc thông tin sản phẩm đã thay đổi. Vui lòng kiểm tra và xác nhận lại.",
                    "cart_changed");
        }


        for (Map.Entry<Long, Long> entry : quantityByProduct.entrySet()) {
            Product product = lockedProducts.get(entry.getKey());
            long requested = entry.getValue();
            if (requested <= 0 || product.getStock() < requested) {
                throw new BusinessException(
                        "Sản phẩm " + product.getName() + " chỉ còn " + product.getStock()
                                + " đôi trong kho nhưng giỏ đang có " + requested + " đôi ở các kích cỡ.",
                        "stock");
            }
        }

        Order order = new Order();
        order.setUser(lockedUser);
        order.setReceiverName(form.getReceiverName().trim());
        order.setPhone(form.getPhone().trim());
        order.setAddress(form.getAddress().trim());
        order.setCheckoutToken(form.getCheckoutToken());

        for (SavedCartItem row : cartRows) {
            Product product = lockedProducts.get(row.getProduct().getId());
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setProductName(product.getName());
            item.setProductBrand(product.getBrand());
            item.setSelectedSize(row.getSelectedSize());
            item.setQuantity(row.getQuantity());
            item.setPrice(product.getPrice());
            order.getItems().add(item);
        }
        for (Map.Entry<Long, Long> entry : quantityByProduct.entrySet()) {
            Product product = lockedProducts.get(entry.getKey());
            product.setStock((int) (product.getStock() - entry.getValue()));
        }

        BigDecimal total = order.getItems().stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(MAX_ORDER_TOTAL) > 0) {
            throw new BusinessException("Tổng giá trị đơn hàng vượt giới hạn cho phép.", "total_limit");
        }
        order.setTotal(total);

        Order saved = orders.saveAndFlush(order);
        savedCartItems.deleteAll(cartRows);
        savedCartItems.flush();
        return saved;
    }

    @Transactional
    public void updateStatus(Long orderId, OrderStatus newStatus) {
        Order order = orders.findByIdWithLock(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng"));
        if (newStatus == null) throw new BusinessException("Trạng thái đơn hàng không hợp lệ.");
        if (order.getStatus() == newStatus) return;
        if (!order.getStatus().canTransitionTo(newStatus)) {
            throw new BusinessException("Không thể chuyển trạng thái đơn hàng theo yêu cầu.", "invalid_status");
        }

        if (newStatus == OrderStatus.DA_HUY) {
            restoreStockInStableOrder(order.getItems());
        }

        order.setStatus(newStatus);
        if (newStatus == OrderStatus.HOAN_THANH && order.getCompletedAt() == null) {
            order.setCompletedAt(LocalDateTime.now());
        }
    }

    private Map<Long, Product> lockProductsInStableOrder(List<SavedCartItem> cartRows) {
        Map<Long, Product> locked = new LinkedHashMap<>();
        cartRows.stream()
                .map(row -> row.getProduct().getId())
                .distinct()
                .sorted()
                .forEach(productId -> locked.put(productId,
                        products.findByIdWithLock(productId)
                                .orElseThrow(() -> new ResourceNotFoundException("Product not found"))));
        return locked;
    }

    private void restoreStockInStableOrder(List<OrderItem> orderItems) {
        Map<Long, Long> quantityByProduct = new LinkedHashMap<>();
        orderItems.stream()
                .sorted(Comparator.comparing((OrderItem item) -> item.getProduct().getId())
                        .thenComparing(OrderItem::getSelectedSize,
                                Comparator.nullsFirst(String::compareToIgnoreCase)))
                .forEach(item -> quantityByProduct.merge(
                        item.getProduct().getId(), (long) item.getQuantity(), Long::sum));

        quantityByProduct.keySet().stream().sorted().forEach(productId -> {
            Product product = products.findByIdWithLock(productId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Không tìm thấy sản phẩm cần hoàn lại tồn kho"));
            long restoredStock = (long) product.getStock() + quantityByProduct.get(productId);
            if (restoredStock > Integer.MAX_VALUE) {
                throw new BusinessException(
                        "Không thể hoàn lại tồn kho vì số lượng vượt giới hạn hệ thống.",
                        "stock_overflow");
            }
            product.setStock((int) restoredStock);
        });
    }

    private String normalizeOrderSize(Product product, String selectedSize) {
        String cleaned = selectedSize == null ? "" : selectedSize.trim();
        return product.getAvailableSizes().stream()
                .filter(size -> size.equalsIgnoreCase(cleaned))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "Kích cỡ của sản phẩm " + product.getName()
                                + " không còn hợp lệ. Vui lòng cập nhật lại giỏ hàng.",
                        "invalid_size"));
    }
}
