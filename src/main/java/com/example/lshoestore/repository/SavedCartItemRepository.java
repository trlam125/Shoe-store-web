package com.example.lshoestore.repository;

import com.example.lshoestore.model.SavedCartItem;
import com.example.lshoestore.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface SavedCartItemRepository extends JpaRepository<SavedCartItem, Long> {
    @EntityGraph(attributePaths = "product")
    List<SavedCartItem> findByUserOrderByProduct_IdAscSelectedSizeAsc(User user);

    Optional<SavedCartItem> findByUserAndProductIdAndSelectedSize(User user, Long productId, String selectedSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SavedCartItem s WHERE s.user = :user "
            + "AND s.product.id = :productId AND s.selectedSize = :selectedSize")
    Optional<SavedCartItem> findByUserAndProductIdAndSelectedSizeWithLock(
            @Param("user") User user,
            @Param("productId") Long productId,
            @Param("selectedSize") String selectedSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SavedCartItem s WHERE s.user = :user AND s.product.id = :productId "
            + "ORDER BY s.selectedSize, s.id")
    List<SavedCartItem> findAllByUserAndProductIdWithLock(@Param("user") User user,
                                                          @Param("productId") Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SavedCartItem s JOIN FETCH s.product WHERE s.user = :user "
            + "ORDER BY s.product.id, s.selectedSize, s.id")
    List<SavedCartItem> findByUserWithLock(@Param("user") User user);

    @Query(value = """
            SELECT COALESCE(SUM(LEAST(
                GREATEST(s.quantity, 0)::BIGINT,
                GREATEST(v.stock, 0)::BIGINT
            )), 0)::BIGINT
            FROM saved_cart_items s
            JOIN product p ON p.id = s.product_id
            JOIN product_variant v ON v.product_id = s.product_id
                AND LOWER(BTRIM(v.size)) = LOWER(BTRIM(s.selected_size))
            WHERE s.user_id = :userId
              AND p.active = TRUE
              AND v.enabled = TRUE
              AND v.stock > 0
            """, nativeQuery = true)
    Long countAvailableQuantityByUserId(@Param("userId") Long userId);

    @Modifying
    @Transactional
    void deleteByUser(User user);
}
