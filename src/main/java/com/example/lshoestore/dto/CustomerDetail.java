package com.example.lshoestore.dto;

import com.example.lshoestore.model.Order;
import com.example.lshoestore.model.User;

import java.math.BigDecimal;
import java.util.List;

public class CustomerDetail {
    private final User user;
    private final List<Order> orders;
    private final long orderCount;
    private final long completedOrderCount;
    private final BigDecimal completedSpend;

    public CustomerDetail(User user, List<Order> orders, long orderCount,
                          long completedOrderCount, BigDecimal completedSpend) {
        this.user = user;
        this.orders = orders;
        this.orderCount = orderCount;
        this.completedOrderCount = completedOrderCount;
        this.completedSpend = completedSpend;
    }

    public User getUser() { return user; }
    public List<Order> getOrders() { return orders; }
    public long getOrderCount() { return orderCount; }
    public long getCompletedOrderCount() { return completedOrderCount; }
    public BigDecimal getCompletedSpend() { return completedSpend; }
}
