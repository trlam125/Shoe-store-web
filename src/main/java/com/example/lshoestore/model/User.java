package com.example.lshoestore.model;

import jakarta.persistence.*;

import java.util.Locale;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 190)
    private String email;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 30)
    private String role = "ROLE_USER";

    @Column(length = 20)
    private String phone;

    @Column(length = 500)
    private String address;

    @Column
    private Integer sessionVersion = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public int getSessionVersion() { return sessionVersion == null ? 0 : sessionVersion; }
    public void setSessionVersion(int sessionVersion) { this.sessionVersion = sessionVersion; }
    public void revokeSessions() { this.sessionVersion = getSessionVersion() + 1; }
}
