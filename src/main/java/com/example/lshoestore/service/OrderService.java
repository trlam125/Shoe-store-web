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
import java.util.List;

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
    public Order placeOrder(User user, CheckoutForm form) {
        User lockedUser = users.findByIdWithLock(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản người dùng"));

        if (orders.existsByCheckoutToken(form.getCheckoutToken())) {
            throw new BusinessException("Đơn hàng này đã được tạo trước đó.", "duplicate_checkout");
        }

        List<SavedCartItem> cartRows = savedCartItems.findByUserWithLock(lockedUser);
        if (cartRows.isEmpty()) {
            throw new BusinessException("Giỏ hàng của bạn đang trống.", "empty_cart");
        }

        Order order = new Order();
        order.setUser(lockedUser);
        order.setReceiverName(form.getReceiverName().trim());
        order.setPhone(form.getPhone().trim());
        order.setAddress(form.getAddress().trim());
        order.setCheckoutToken(form.getCheckoutToken());

        for (SavedCartItem row : cartRows) {
            Product product = products.findByIdWithLock(row.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            if (!product.isActive()) {
                throw new BusinessException("Sản phẩm " + product.getName() + " hiện không còn bán.", "inactive");
            }
            if (row.getQuantity() <= 0) {
                throw new BusinessException("Số lượng sản phẩm không hợp lệ.", "quantity");
            }
            if (product.getStock() < row.getQuantity()) {
                throw new BusinessException(
                        "Sản phẩm " + product.getName() + " chỉ còn " + product.getStock() + " đôi trong kho.",
                        "stock");
            }

            product.setStock(product.getStock() - row.getQuantity());
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setQuantity(row.getQuantity());
            item.setPrice(product.getPrice());
            order.getItems().add(item);
        }

        BigDecimal total = order.getItems().stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(MAX_ORDER_TOTAL) > 0) {
            throw new BusinessException("Tong gia tri don hang vuot gioi han cho phep.", "total_limit");
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
            for (OrderItem item : order.getItems()) {
                products.findByIdWithLock(item.getProduct().getId())
                        .ifPresent(product -> product.setStock(product.getStock() + item.getQuantity()));
            }
        }

        order.setStatus(newStatus);
        if (newStatus == OrderStatus.HOAN_THANH && order.getCompletedAt() == null) {
            order.setCompletedAt(LocalDateTime.now());
        }
    }
}
