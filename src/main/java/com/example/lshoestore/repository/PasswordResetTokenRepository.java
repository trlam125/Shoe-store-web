package com.example.lshoestore.repository;

import com.example.lshoestore.model.PasswordResetToken;
import com.example.lshoestore.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PasswordResetToken t JOIN FETCH t.user WHERE t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashWithLock(@Param("tokenHash") String tokenHash);

    void deleteByUser(User user);

    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
