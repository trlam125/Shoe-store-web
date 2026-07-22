package com.example.lshoestore.service;

import com.example.lshoestore.dto.CustomerDetail;
import com.example.lshoestore.dto.CustomerSummary;
import com.example.lshoestore.dto.CustomerUpdateForm;
import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.exception.ResourceNotFoundException;
import com.example.lshoestore.model.OrderStatus;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.OrderRepository;
import com.example.lshoestore.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CustomerAdminService {
    private static final String CUSTOMER_ROLE = "ROLE_USER";
    private final UserRepository users;
    private final OrderRepository orders;

    public CustomerAdminService(UserRepository users, OrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public Page<CustomerSummary> search(String keyword, Boolean enabled, Pageable pageable) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        Page<User> userPage = users.searchByRoleForAdmin(CUSTOMER_ROLE, normalizedKeyword, enabled, pageable);
        List<CustomerSummary> summaries = userPage.getContent().stream()
                .map(this::toSummary)
                .toList();
        return new PageImpl<>(summaries, pageable, userPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CustomerDetail getDetail(Long id) {
        User user = users.findByIdAndRoleIgnoreCase(id, CUSTOMER_ROLE)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản khách hàng."));
        List<com.example.lshoestore.model.Order> customerOrders = orders.findByUserOrderByCreatedAtDesc(user);
        return new CustomerDetail(
                user,
                customerOrders,
                customerOrders.size(),
                orders.countByUserAndStatus(user, OrderStatus.HOAN_THANH),
                safeMoney(orders.sumTotalByUserAndStatus(user, OrderStatus.HOAN_THANH))
        );
    }


    @Transactional(readOnly = true)
    public void assertProfileEditable(Long id) {
        User user = users.findByIdAndRoleIgnoreCase(id, CUSTOMER_ROLE)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản khách hàng."));
        requireEditableCustomer(user);
    }

    @Transactional(readOnly = true)
    public CustomerUpdateForm getUpdateForm(Long id) {
        User user = users.findByIdAndRoleIgnoreCase(id, CUSTOMER_ROLE)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản khách hàng."));
        requireEditableCustomer(user);
        CustomerUpdateForm form = new CustomerUpdateForm();
        form.setFullName(user.getFullName());
        form.setPhone(user.getPhone());
        form.setAddress(user.getAddress());
        return form;
    }

    @Transactional
    public void updateProfile(Long id, CustomerUpdateForm form) {
        User user = users.findByIdAndRoleIgnoreCaseWithLock(id, CUSTOMER_ROLE)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản khách hàng."));
        requireEditableCustomer(user);
        user.setFullName(form.getFullName().trim());
        user.setPhone(trimToNull(form.getPhone()));
        user.setAddress(trimToNull(form.getAddress()));
    }

    @Transactional
    public boolean toggleEnabled(Long id) {
        User user = users.findByIdAndRoleIgnoreCaseWithLock(id, CUSTOMER_ROLE)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản khách hàng."));

        user.setEnabled(!user.isEnabled());
        user.revokeSessions();
        return user.isEnabled();
    }

    private void requireEditableCustomer(User user) {
        if (!CUSTOMER_ROLE.equalsIgnoreCase(user.getRole())) {
            throw new BusinessException(
                    "Tài khoản này không thuộc nhóm khách hàng và không thể chỉnh sửa tại đây.",
                    "not_customer");
        }
    }

    private CustomerSummary toSummary(User user) {
        return new CustomerSummary(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt(),
                orders.countByUser(user),
                safeMoney(orders.sumTotalByUserAndStatus(user, OrderStatus.HOAN_THANH))
        );
    }


    private String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
