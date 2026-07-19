package com.example.lshoestore.repository;

import com.example.lshoestore.model.PendingRegistration;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, Long> {
    Optional<PendingRegistration> findByRegistrationToken(String registrationToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PendingRegistration p WHERE p.registrationToken = :registrationToken")
    Optional<PendingRegistration> findByRegistrationTokenWithLock(
            @Param("registrationToken") String registrationToken);

    List<PendingRegistration> findAllByEmailIgnoreCase(String email);

    Optional<PendingRegistration> findFirstByEmailIgnoreCaseOrderByIdDesc(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PendingRegistration p WHERE LOWER(p.email) = LOWER(:email) ORDER BY p.id DESC")
    List<PendingRegistration> findAllByEmailIgnoreCaseWithLock(@Param("email") String email);

    @Modifying
    @Query("DELETE FROM PendingRegistration p "
            + "WHERE LOWER(p.email) = LOWER(:email) AND p.id < :latestId")
    int deleteOlderByEmailIgnoreCase(@Param("email") String email,
                                     @Param("latestId") Long latestId);

    long deleteByEmailIgnoreCase(String email);

    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
