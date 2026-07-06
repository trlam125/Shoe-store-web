package com.example.lshoestore.service;

import com.example.lshoestore.repository.UserRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var u = userRepository.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản"));
        return org.springframework.security.core.userdetails.User.withUsername(u.getEmail()).password(u.getPassword()).roles(u.getRole().replace("ROLE_", "")).build();
    }
}
