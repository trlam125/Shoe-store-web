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
    private final boolean allowLocalLinkLogging;

    public PasswordResetService(UserRepository users,
                                PasswordResetTokenRepository tokens,
                                PasswordEncoder passwordEncoder,
                                ObjectProvider<JavaMailSender> mailSenderProvider,
                                @Value("${spring.mail.username:}") String mailUsername,
                                @Value("${app.password-reset.expiry-minutes:30}") int expiryMinutes,
                                @Value("${app.password-reset.log-link:false}") boolean logResetLink,
                                @Value("${app.environment:production}") String environment) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.mailUsername = mailUsername == null ? "" : mailUsername.trim();
        this.expiryMinutes = Math.max(expiryMinutes, 5);
        this.allowLocalLinkLogging = logResetLink && "development".equalsIgnoreCase(environment);
    }

    @Transactional
    public void requestReset(String email, String baseUrl) {
        if (email == null || email.isBlank() || email.length() > 190 || baseUrl == null || baseUrl.isBlank()) return;
        Optional<User> optionalUser = users.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT));
        if (optionalUser.isEmpty()) return;

        User user = users.findByIdWithLock(optionalUser.get().getId()).orElse(null);
        if (user == null) return;
        tokens.deleteByUser(user);
        tokens.flush();

        String rawToken = generateToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(hash(rawToken));
        token.setExpiresAt(LocalDateTime.now().plusMinutes(expiryMinutes));
        tokens.save(token);

        String resetUrl = baseUrl.replaceAll("/+$", "") + "/reset-password?token=" + rawToken;
        sendResetEmail(user, resetUrl);
    }

    @Transactional(readOnly = true)
    public boolean isValid(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return false;
        return tokens.findByTokenHash(hash(rawToken))
                .filter(token -> token.getUsedAt() == null)
                .filter(token -> token.getExpiresAt().isAfter(LocalDateTime.now()))
                .isPresent();
    }

    @Transactional
    public boolean resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) return false;
        Optional<PasswordResetToken> optional = tokens.findByTokenHashWithLock(hash(rawToken));
        if (optional.isEmpty()) return false;

        PasswordResetToken token = optional.get();
        LocalDateTime now = LocalDateTime.now();
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) return false;

        token.setUsedAt(now);
        tokens.saveAndFlush(token);

        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        user.revokeSessions();
        users.save(user);
        tokens.deleteByUser(user);
        return true;
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
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void sendResetEmail(User user, String resetUrl) {
        if (mailSender == null || mailUsername.isBlank()) {
            logLocalLink(resetUrl);
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
            log.error("Password reset email could not be sent to {}", user.getEmail());
            logLocalLink(resetUrl);
        }
    }

    private void logLocalLink(String resetUrl) {
        if (allowLocalLinkLogging) {
            log.warn("LOCAL DEVELOPMENT reset link: {}", resetUrl);
        } else {
            log.info("Password reset email was not sent because SMTP is unavailable.");
        }
    }
}
