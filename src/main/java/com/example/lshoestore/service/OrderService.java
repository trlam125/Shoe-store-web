package com.example.lshoestore.service;

import com.example.lshoestore.model.*;
import com.example.lshoestore.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductRepository products;

    public OrderService(OrderRepository orders, ProductRepository products) {
        this.orders = orders;
        this.products = products;
    }

    /**
     * Tạo đơn hàng trong một transaction với pessimistic lock.
     * Ném IllegalStateException nếu có sản phẩm inactive hoặc thiếu stock.
     */
    @Transactional
    public Order placeOrder(User user,
                            String receiverName, String phone, String address,
                            Collection<CartItem> cartItems) {

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

        return orders.save(o);
    }
}
