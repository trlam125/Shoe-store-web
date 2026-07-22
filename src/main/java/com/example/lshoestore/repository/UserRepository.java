package com.example.lshoestore.repository;

import com.example.lshoestore.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByRoleIgnoreCase(String role);
    long countByRoleIgnoreCase(String role);
    long countByRoleIgnoreCaseAndEnabledFalse(String role);

    @Query("""
            SELECT u FROM User u
            WHERE LOWER(u.role) = LOWER(:role)
              AND (:keyword = ''
                OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR COALESCE(u.phone, '') LIKE CONCAT('%', :keyword, '%'))
              AND (:enabled IS NULL OR u.enabled = :enabled)
            """)
    Page<User> searchByRoleForAdmin(@Param("role") String role,
                                    @Param("keyword") String keyword,
                                    @Param("enabled") Boolean enabled,
                                    Pageable pageable);

    Optional<User> findByIdAndRoleIgnoreCase(Long id, String role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id AND LOWER(u.role) = LOWER(:role)")
    Optional<User> findByIdAndRoleIgnoreCaseWithLock(@Param("id") Long id,
                                                     @Param("role") String role);
}
