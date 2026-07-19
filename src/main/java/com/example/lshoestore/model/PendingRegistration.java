package com.example.lshoestore.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "pending_registrations", indexes = {
        @Index(name = "idx_pending_registration_email", columnList = "email"),
        @Index(name = "idx_pending_registration_expires", columnList = "expires_at"),
        @Index(name = "uk_pending_registration_token", columnList = "registration_token", unique = true)
})
public class PendingRegistration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "registration_token", nullable = false, unique = true, length = 64)
    private String registrationToken;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 190)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(length = 20)
    private String phone;

    @Column(length = 500)
    private String address;

    @Column(name = "verification_code_hash", nullable = false, length = 100)
    private String verificationCodeHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt = LocalDateTime.now();

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    public Long getId() { return id; }
    public String getRegistrationToken() { return registrationToken; }
    public void setRegistrationToken(String registrationToken) { this.registrationToken = registrationToken; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getVerificationCodeHash() { return verificationCodeHash; }
    public void setVerificationCodeHash(String verificationCodeHash) { this.verificationCodeHash = verificationCodeHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastSentAt() { return lastSentAt; }
    public void setLastSentAt(LocalDateTime lastSentAt) { this.lastSentAt = lastSentAt; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
}
