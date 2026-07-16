package com.example.lshoestore.service;

import com.example.lshoestore.model.PasswordResetToken;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.PasswordResetTokenRepository;
import com.example.lshoestore.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String mailUsername;
    private final int expiryMinutes;
    private final boolean logResetLink;

    public PasswordResetService(
            UserRepository users,
            PasswordResetTokenRepository tokens,
            PasswordEncoder passwordEncoder,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${app.password-reset.expiry-minutes:30}") int expiryMinutes,
            @Value("${app.password-reset.log-link:true}") boolean logResetLink) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.mailUsername = mailUsername;
        this.expiryMinutes = Math.max(expiryMinutes, 5);
        this.logResetLink = logResetLink;
    }

    @Transactional
    public void requestReset(String email, String baseUrl) {
        if (email == null || email.isBlank()) {
            return;
        }
        Optional<User> optionalUser = users.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT));
        if (optionalUser.isEmpty()) {
            return;
        }

        User user = optionalUser.get();
        tokens.deleteByUser(user);

        String rawToken = generateToken();
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUser(user);
        resetToken.setTokenHash(hash(rawToken));
        resetToken.setExpiresAt(LocalDateTime.now().plusMinutes(expiryMinutes));
        tokens.save(resetToken);

        String resetUrl = baseUrl.replaceAll("/+$", "") + "/reset-password?token=" + rawToken;
        sendResetEmail(user, resetUrl);
    }

    @Transactional(readOnly = true)
    public boolean isValid(String rawToken) {
        return findValid(rawToken).isPresent();
    }

    @Transactional
    public boolean resetPassword(String rawToken, String newPassword) {
        Optional<PasswordResetToken> optionalToken = findValid(rawToken);
        if (optionalToken.isEmpty()) {
            return false;
        }

        User user = optionalToken.get().getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        users.save(user);
        tokens.deleteByUser(user);
        return true;
    }

    private Optional<PasswordResetToken> findValid(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(hash(rawToken))
                .filter(token -> token.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void sendResetEmail(User user, String resetUrl) {
        if (mailSender == null || mailUsername.isBlank()) {
            logLink(resetUrl);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailUsername);
        message.setTo(user.getEmail());
        message.setSubject("Đặt lại mật khẩu LSHOE");
        message.setText("Xin chào " + user.getFullName() + ",\n\n"
                + "Mở liên kết sau để đặt lại mật khẩu. Liên kết hết hạn sau "
                + expiryMinutes + " phút:\n" + resetUrl
                + "\n\nNếu bạn không yêu cầu thao tác này, hãy bỏ qua email.");
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            log.error("Không thể gửi email đặt lại mật khẩu tới {}", user.getEmail(), exception);
            logLink(resetUrl);
        }
    }

    private void logLink(String resetUrl) {
        if (logResetLink) {
            log.warn("SMTP chưa được cấu hình. Liên kết đặt lại mật khẩu dùng cho local: {}", resetUrl);
        }
    }
}
