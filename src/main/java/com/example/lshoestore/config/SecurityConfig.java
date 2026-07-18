package com.example.lshoestore.config;

import com.example.lshoestore.security.SessionVersionFilter;
import com.example.lshoestore.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    CustomUserDetailsService userDetailsService,
                                    CartMergeLoginHandler loginHandler,
                                    SessionVersionFilter sessionVersionFilter) throws Exception {
        http
                .userDetailsService(userDetailsService)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/ai/image-search", "/ai/image-search/analyze").permitAll()
                        .requestMatchers("/admin/**", "/ai/**").hasRole("ADMIN")
                        .requestMatchers("/checkout", "/orders/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("email")
                        .successHandler(loginHandler)
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll())
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .exceptionHandling(exception -> exception
                        .accessDeniedPage("/access-denied"))
                .addFilterAfter(sessionVersionFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
