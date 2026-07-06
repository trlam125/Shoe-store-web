package com.example.lshoestore.repository;

import com.example.lshoestore.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByActiveTrueOrderByIdDesc();

    List<Product> findByActiveTrueAndNameContainingIgnoreCaseOrderByIdDesc(String keyword);

    List<Product> findByActiveTrueAndCategory_IdOrderByIdDesc(Long categoryId);
}
