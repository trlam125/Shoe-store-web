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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String mailUsername;
    private final int expiryMinutes;
    private final int maxSendAttempts;
    private final boolean allowLocalLinkLogging;

    public PasswordResetService(UserRepository users,
                                PasswordResetTokenRepository tokens,
                                PasswordEncoder passwordEncoder,
                                ObjectProvider<JavaMailSender> mailSenderProvider,
                                PlatformTransactionManager transactionManager,
                                @Value("${spring.mail.username:}") String mailUsername,
                                @Value("${app.password-reset.expiry-minutes:30}") int expiryMinutes,
                                @Value("${app.mail.max-send-attempts:3}") int maxSendAttempts,
                                @Value("${app.password-reset.log-link:false}") boolean logResetLink,
                                @Value("${app.environment:production}") String environment) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.mailUsername = mailUsername == null ? "" : mailUsername.trim();
        this.expiryMinutes = Math.max(expiryMinutes, 5);
        this.maxSendAttempts = Math.max(maxSendAttempts, 1);
        this.allowLocalLinkLogging = logResetLink && "development".equalsIgnoreCase(environment);
    }

    public RequestResult requestReset(String email, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return RequestResult.PUBLIC_URL_UNAVAILABLE;
        if (email == null || email.isBlank() || email.length() > 190) return RequestResult.NOT_FOUND;
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        ResetPreparation preparation = transactionTemplate.execute(status -> {
            Optional<User> optionalUser = users.findByEmailIgnoreCase(normalizedEmail);
            if (optionalUser.isEmpty()) {
                return new ResetPreparation(RequestResult.NOT_FOUND, null, null);
            }

            User user = users.findByIdWithLock(optionalUser.get().getId()).orElse(null);
            if (user == null) {
                return new ResetPreparation(RequestResult.NOT_FOUND, null, null);
            }

            // Keep existing valid reset links until one is used; only remove expired rows.
            tokens.deleteByExpiresAtBefore(LocalDateTime.now());
            String rawToken = generateToken();
            PasswordResetToken token = new PasswordResetToken();
            token.setUser(user);
            token.setTokenHash(hash(rawToken));
            token.setExpiresAt(LocalDateTime.now().plusMinutes(expiryMinutes));
            tokens.saveAndFlush(token);

            String resetUrl = baseUrl.replaceAll("/+$", "") + "/reset-password?token=" + rawToken;
            return new ResetPreparation(
                    RequestResult.SENT,
                    token.getId(),
                    new ResetMailRequest(user.getEmail(), user.getFullName(), resetUrl));
        });

        if (preparation == null) return RequestResult.DELIVERY_FAILED;
        if (preparation.result() != RequestResult.SENT) return preparation.result();

        if (!sendResetEmail(preparation.mail())) {
            deleteCandidateToken(preparation.tokenId());
            return RequestResult.DELIVERY_FAILED;
        }

        // Multiple successfully delivered links may coexist. Using any one of them
        // changes the password and deletes every reset token for that user.
        return RequestResult.SENT;
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
        if (rawToken == null || rawToken.isBlank() || !PasswordPolicy.isValidForBcrypt(newPassword)) return false;
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

    private void deleteCandidateToken(Long tokenId) {
        if (tokenId == null) return;
        transactionTemplate.executeWithoutResult(status -> {
            if (tokens.existsById(tokenId)) {
                tokens.deleteById(tokenId);
            }
        });
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

    private boolean sendResetEmail(ResetMailRequest request) {
        if (request == null) return false;
        if (mailSender == null || mailUsername.isBlank()) {
            return logLocalLink(request);
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailUsername);
        message.setTo(request.recipientEmail());
        message.setSubject("Đặt lại mật khẩu LSHOE");
        message.setText("Xin chào " + request.recipientName() + ",\n\n"
                + "Mở liên kết sau để đặt lại mật khẩu. Liên kết hết hạn sau "
                + expiryMinutes + " phút:\n" + request.resetUrl()
                + "\n\nNếu bạn không yêu cầu thao tác này, hãy bỏ qua email.");

        for (int attempt = 1; attempt <= maxSendAttempts; attempt++) {
            try {
                mailSender.send(message);
                return true;
            } catch (MailException exception) {
                log.warn("Password-reset email attempt {}/{} failed for {}",
                        attempt, maxSendAttempts, request.recipientEmail(), exception);
            }
        }
        return logLocalLink(request);
    }

    private boolean logLocalLink(ResetMailRequest request) {
        if (allowLocalLinkLogging) {
            log.warn("LOCAL DEVELOPMENT reset link for {}: {}",
                    request.recipientEmail(), request.resetUrl());
            return true;
        }
        log.error("Password-reset email was not delivered to {}", request.recipientEmail());
        return false;
    }

    private record ResetMailRequest(String recipientEmail, String recipientName, String resetUrl) {}

    private record ResetPreparation(RequestResult result,
                                    Long tokenId, ResetMailRequest mail) {}

    public enum RequestResult {
        SENT,
        NOT_FOUND,
        PUBLIC_URL_UNAVAILABLE,
        DELIVERY_FAILED
    }
}
