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
    Page<Product> findByActiveTrueAndCategory_IdOrderByIdDesc(Long categoryId, Pageable pageable);

    @Query(value = """
            SELECT p
            FROM Product p
            WHERE p.active = true
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (
                    LOCATE(LOWER(:keyword), LOWER(p.name)) > 0
                    OR LOCATE(LOWER(:keyword), LOWER(p.brand)) > 0
                    OR LOCATE(LOWER(:keyword), LOWER(COALESCE(p.description, ''))) > 0
                  )
            ORDER BY p.id DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Product p
            WHERE p.active = true
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (
                    LOCATE(LOWER(:keyword), LOWER(p.name)) > 0
                    OR LOCATE(LOWER(:keyword), LOWER(p.brand)) > 0
                    OR LOCATE(LOWER(:keyword), LOWER(COALESCE(p.description, ''))) > 0
                  )
            """)
    Page<Product> searchActiveCatalog(@Param("keyword") String keyword,
                                      @Param("categoryId") Long categoryId,
                                      Pageable pageable);

    Page<Product> findAllByOrderByIdDesc(Pageable pageable);
    long countByActiveTrue();
    boolean existsByImageUrl(String imageUrl);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);
}
