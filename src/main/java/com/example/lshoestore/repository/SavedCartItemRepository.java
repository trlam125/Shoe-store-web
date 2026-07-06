package com.example.lshoestore.repository;

import com.example.lshoestore.model.SavedCartItem;
import com.example.lshoestore.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedCartItemRepository extends JpaRepository<SavedCartItem, Long> {

    List<SavedCartItem> findByUser(User user);

    Optional<SavedCartItem> findByUserAndProductId(User user, Long productId);

    void deleteByUser(User user);
}
