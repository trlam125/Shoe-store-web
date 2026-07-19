package com.example.lshoestore.repository;

import com.example.lshoestore.model.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findTop12ByActiveTrueOrderByIdDesc();
    List<Product> findByActiveTrueOrderByIdDesc();
    List<Product> findByActiveTrueAndStockGreaterThanOrderByIdDesc(int stock);
    List<Product> findByActiveTrueAndNameContainingIgnoreCaseOrderByIdDesc(String keyword);
    List<Product> findByActiveTrueAndCategory_IdOrderByIdDesc(Long categoryId);

    Page<Product> findByActiveTrueOrderByIdDesc(Pageable pageable);
    Page<Product> findByActiveTrueAndNameContainingIgnoreCaseOrderByIdDesc(String keyword, Pageable pageable);
    Page<Product> findByActiveTrueAndCategory_IdOrderByIdDesc(Long categoryId, Pageable pageable);
    Page<Product> findByActiveTrueAndCategory_IdAndNameContainingIgnoreCaseOrderByIdDesc(
            Long categoryId, String keyword, Pageable pageable);

    Page<Product> findAllByOrderByIdDesc(Pageable pageable);
    long countByActiveTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);
}
