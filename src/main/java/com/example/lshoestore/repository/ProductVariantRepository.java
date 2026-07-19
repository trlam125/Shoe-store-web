package com.example.lshoestore.repository;

import com.example.lshoestore.model.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    @Query("SELECT v FROM ProductVariant v WHERE v.product.id = :productId "
            + "ORDER BY LOWER(v.size), v.id")
    List<ProductVariant> findAllByProductId(@Param("productId") Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ProductVariant v WHERE v.product.id = :productId "
            + "ORDER BY LOWER(v.size), v.id")
    List<ProductVariant> findAllByProductIdWithLock(@Param("productId") Long productId);

    @Query("SELECT v FROM ProductVariant v WHERE v.product.id = :productId "
            + "AND LOWER(TRIM(v.size)) = LOWER(TRIM(:size))")
    Optional<ProductVariant> findByProductIdAndSize(@Param("productId") Long productId,
                                                    @Param("size") String size);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ProductVariant v WHERE v.product.id = :productId "
            + "AND LOWER(TRIM(v.size)) = LOWER(TRIM(:size))")
    Optional<ProductVariant> findByProductIdAndSizeWithLock(@Param("productId") Long productId,
                                                            @Param("size") String size);
}
