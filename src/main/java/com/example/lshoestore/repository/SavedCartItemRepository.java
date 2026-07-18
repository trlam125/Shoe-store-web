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
    List<SavedCartItem> findByUserOrderByIdAsc(User user);

    Optional<SavedCartItem> findByUserAndProductId(User user, Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SavedCartItem s WHERE s.user = :user AND s.product.id = :productId")
    Optional<SavedCartItem> findByUserAndProductIdWithLock(@Param("user") User user,
                                                           @Param("productId") Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SavedCartItem s JOIN FETCH s.product WHERE s.user = :user ORDER BY s.id")
    List<SavedCartItem> findByUserWithLock(@Param("user") User user);

    @Modifying
    @Transactional
    void deleteByUser(User user);
}
