package com.example.lshoestore.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class CustomerSummary {
    private final Long id;
    private final String fullName;
    private final String email;
    private final String phone;
    private final String role;
    private final boolean enabled;
    private final LocalDateTime createdAt;
    private final long orderCount;
    private final BigDecimal completedSpend;

    public CustomerSummary(Long id, String fullName, String email, String phone,
                           String role, boolean enabled, LocalDateTime createdAt,
                           long orderCount, BigDecimal completedSpend) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.role = role;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.orderCount = orderCount;
        this.completedSpend = completedSpend;
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public long getOrderCount() { return orderCount; }
    public BigDecimal getCompletedSpend() { return completedSpend; }
}
