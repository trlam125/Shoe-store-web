package com.example.lshoestore.service;

import com.example.lshoestore.model.*;
import com.example.lshoestore.repository.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductRepository products;
    private final CartService cartService;

    public OrderService(OrderRepository orders, ProductRepository products, CartService cartService) {
        this.orders = orders;
        this.products = products;
        this.cartService = cartService;
    }

    /**
     * Tạo đơn hàng trong một transaction với pessimistic lock.
     * Cart được clear trong cùng transaction để đảm bảo tính nhất quán.
     * Ném IllegalStateException nếu giỏ hàng rỗng, sản phẩm inactive hoặc thiếu stock.
     */
    @Transactional
    public Order placeOrder(User user,
                            String receiverName, String phone, String address,
                            Collection<CartItem> cartItems,
                            Authentication auth, HttpSession session) {

        // Fix #8: kiểm tra giỏ hàng rỗng trong transaction
        if (cartItems == null || cartItems.isEmpty()) {
            throw new IllegalStateException("Giỏ hàng của bạn đang trống.|empty");
        }

        Order o = new Order();
        o.setUser(user);
        o.setReceiverName(receiverName);
        o.setPhone(phone);
        o.setAddress(address);

        for (CartItem ci : cartItems) {
            Product p = products.findByIdWithLock(ci.getProduct().getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Sản phẩm không tồn tại: " + ci.getProduct().getId()));

            if (!p.isActive()) {
                throw new IllegalStateException(
                        "Sản phẩm \"" + p.getName() + "\" hiện không còn bán.|inactive");
            }
            if (p.getStock() < ci.getQuantity()) {
                throw new IllegalStateException(
                        "Sản phẩm \"" + p.getName() + "\" chỉ còn " + p.getStock() + " đôi trong kho.|stock");
            }

            p.setStock(p.getStock() - ci.getQuantity());

            OrderItem it = new OrderItem();
            it.setOrder(o);
            it.setProduct(p);
            it.setQuantity(ci.getQuantity());
            it.setPrice(p.getPrice()); // lưu giá tại thời điểm mua
            o.getItems().add(it);
        }

        // Tính total từ các item đã validated — không query lại
        o.setTotal(o.getItems().stream()
                .map(it -> it.getPrice().multiply(java.math.BigDecimal.valueOf(it.getQuantity())))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));

        Order saved = orders.save(o);

        // Fix #5: clear cart trong cùng transaction để tránh mất đồng bộ khi server crash
        cartService.clear(auth, session);

        return saved;
    }

    /**
     * Cập nhật trạng thái đơn hàng với state machine và hoàn stock khi hủy.
     */
    @Transactional
    public void updateStatus(Long orderId, OrderStatus newStatus) {
        Order o = orders.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng id=" + orderId));

        // Fix #4: state machine — không cho phép chuyển từ trạng thái cuối
        if (!o.getStatus().canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    "Không thể chuyển đơn hàng từ \"" + o.getStatus().getDisplayName()
                    + "\" sang \"" + newStatus.getDisplayName() + "\".");
        }

        // Fix #3: hoàn stock khi hủy đơn
        if (newStatus == OrderStatus.DA_HUY) {
            for (OrderItem item : o.getItems()) {
                Product p = products.findByIdWithLock(item.getProduct().getId()).orElse(null);
                if (p != null) {
                    p.setStock(p.getStock() + item.getQuantity());
                }
            }
        }

        o.setStatus(newStatus);

        // Fix #7: tự động set completedAt khi hoàn thành
        if (newStatus == OrderStatus.HOAN_THANH && o.getCompletedAt() == null) {
            o.setCompletedAt(LocalDateTime.now());
        }

        orders.save(o);
    }
}
