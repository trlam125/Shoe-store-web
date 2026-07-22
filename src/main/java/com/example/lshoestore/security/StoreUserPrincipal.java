package com.example.lshoestore.security;

import com.example.lshoestore.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

public class StoreUserPrincipal implements UserDetails {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final String email;
    private final String password;
    private final String role;
    private final int sessionVersion;
    private final boolean enabled;

    public StoreUserPrincipal(User user) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.password = user.getPassword();
        this.role = user.getRole();
        this.sessionVersion = user.getSessionVersion();
        this.enabled = user.isEnabled();
    }

    public Long getUserId() { return userId; }
    public int getSessionVersion() { return sessionVersion; }
    public String getRole() { return role; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role));
    }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return enabled; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }
}
