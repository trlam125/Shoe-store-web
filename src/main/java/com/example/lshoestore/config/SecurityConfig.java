package com.example.lshoestore.config;

import com.example.lshoestore.service.CustomUserDetailsService;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration

public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, CustomUserDetailsService uds) throws Exception {
        http.userDetailsService(uds).authorizeHttpRequests(auth -> auth.requestMatchers("/admin/**").hasRole("ADMIN").requestMatchers("/cart/**", "/checkout/**", "/orders/**").authenticated().anyRequest().permitAll()).formLogin(f -> f.loginPage("/login").usernameParameter("email").defaultSuccessUrl("/", true).permitAll()).logout(l -> l.logoutSuccessUrl("/").permitAll()).csrf(csrf -> csrf.disable());
        return http.build();
    }
}
