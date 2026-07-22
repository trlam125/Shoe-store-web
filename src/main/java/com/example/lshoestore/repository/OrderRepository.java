package com.example.lshoestore.repository;

import com.example.lshoestore.model.Order;
import com.example.lshoestore.model.OrderStatus;
import com.example.lshoestore.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserOrderByCreatedAtDesc(User user);
    List<Order> findAllByOrderByCreatedAtDesc();
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByUser(User user);
    long countByUserAndStatus(User user, OrderStatus status);
    boolean existsByCheckoutToken(String checkoutToken);
    boolean existsByCheckoutTokenAndUser(String checkoutToken, User user);
    Optional<Order> findByCheckoutToken(String checkoutToken);

    @Query("SELECT SUM(o.total) FROM Order o WHERE o.user = :user AND o.status = :status")
    BigDecimal sumTotalByUserAndStatus(@Param("user") User user, @Param("status") OrderStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") Long id);
}
