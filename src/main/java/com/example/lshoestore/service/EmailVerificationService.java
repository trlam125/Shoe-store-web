package com.example.lshoestore.service;

import com.example.lshoestore.dto.RegistrationForm;
import com.example.lshoestore.model.PendingRegistration;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.PendingRegistrationRepository;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class EmailVerificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final UserRepository users;
    private final PendingRegistrationRepository pendingRegistrations;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String mailUsername;
    private final int expiryMinutes;
    private final int maxAttempts;
    private final int maxSendAttempts;
    private final boolean allowLocalCodeLogging;

    public EmailVerificationService(UserRepository users,
                                    PendingRegistrationRepository pendingRegistrations,
                                    PasswordEncoder passwordEncoder,
                                    ObjectProvider<JavaMailSender> mailSenderProvider,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${spring.mail.username:}") String mailUsername,
                                    @Value("${app.email-verification.expiry-minutes:10}") int expiryMinutes,
                                    @Value("${app.email-verification.max-attempts:8}") int maxAttempts,
                                    @Value("${app.mail.max-send-attempts:3}") int maxSendAttempts,
                                    @Value("${app.email-verification.log-code:false}") boolean logCode,
                                    @Value("${app.environment:production}") String environment) {
        this.users = users;
        this.pendingRegistrations = pendingRegistrations;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.mailUsername = mailUsername == null ? "" : mailUsername.trim();
        this.expiryMinutes = Math.max(expiryMinutes, 5);
        this.maxAttempts = Math.max(maxAttempts, 3);
        this.maxSendAttempts = Math.max(maxSendAttempts, 1);
        this.allowLocalCodeLogging = logCode && "development".equalsIgnoreCase(environment);
    }

    public StartResponse startRegistration(RegistrationForm form, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new StartResponse(StartResult.PUBLIC_URL_UNAVAILABLE, null);
        }
        String email = normalizeEmail(form.getEmail());

        RegistrationPreparation preparation = transactionTemplate.execute(status -> {
            if (email.isBlank() || users.existsByEmailIgnoreCase(email)) {
                return new RegistrationPreparation(StartResult.EMAIL_ALREADY_USED, null, null, null);
            }

            LocalDateTime now = LocalDateTime.now();
            pendingRegistrations.deleteByExpiresAtBefore(now.minusDays(1));

            PendingRegistration pending = new PendingRegistration();
            pending.setRegistrationToken(generateRequestToken());
            pending.setFullName(form.getFullName().trim());
            pending.setEmail(email);
            pending.setPhone(blankToNull(form.getPhone()));
            pending.setAddress(blankToNull(form.getAddress()));
            pending.setPasswordHash(passwordEncoder.encode(form.getPassword()));
            pending.setCreatedAt(now);

            String code = generateCode();
            applyNewCode(pending, code, now);
            pendingRegistrations.saveAndFlush(pending);

            String verificationUrl = buildVerificationUrl(
                    baseUrl, email, pending.getRegistrationToken(), code);
            MailRequest mail = new MailRequest(email, pending.getFullName(), code, verificationUrl);
            return new RegistrationPreparation(
                    StartResult.STARTED, pending.getId(), pending.getRegistrationToken(), mail);
        });

        if (preparation == null) {
            return new StartResponse(StartResult.EMAIL_DELIVERY_FAILED, null);
        }
        if (preparation.result() != StartResult.STARTED) {
            return new StartResponse(preparation.result(), null);
        }
        if (!sendVerificationEmail(preparation.mail())) {
            deletePendingById(preparation.pendingId());
            return new StartResponse(StartResult.EMAIL_DELIVERY_FAILED, null);
        }

        // A newer request must be the only effective request for this email.
        // Delete only older rows so a concurrently-created newer request is never removed.
        deleteOlderPendingRegistrations(email, preparation.pendingId());
        return new StartResponse(StartResult.STARTED, preparation.requestToken());
    }

    @Transactional(readOnly = true)
    public boolean isRegisteredEmail(String emailValue) {
        String email = normalizeEmail(emailValue);
        return !email.isBlank() && users.existsByEmailIgnoreCase(email);
    }

    @Transactional
    public VerificationResult verify(String emailValue, String requestTokenValue, String codeValue) {
        String email = normalizeEmail(emailValue);
        String requestToken = normalizeRequestToken(requestTokenValue);
        String code = normalizeCode(codeValue);
        if (email.isBlank() || code == null) return VerificationResult.INVALID_CODE;

        Optional<PendingRegistration> optional = resolvePendingWithLock(email, requestToken);
        if (optional.isEmpty()) {
            return users.existsByEmailIgnoreCase(email)
                    ? VerificationResult.ALREADY_VERIFIED
                    : VerificationResult.NOT_FOUND;
        }

        PendingRegistration pending = optional.get();
        LocalDateTime now = LocalDateTime.now();
        if (!pending.getExpiresAt().isAfter(now)) return VerificationResult.EXPIRED;
        if (pending.getFailedAttempts() >= maxAttempts) return VerificationResult.TOO_MANY_ATTEMPTS;

        if (!passwordEncoder.matches(code, pending.getVerificationCodeHash())) {
            pending.setFailedAttempts(pending.getFailedAttempts() + 1);
            pendingRegistrations.save(pending);
            return pending.getFailedAttempts() >= maxAttempts
                    ? VerificationResult.TOO_MANY_ATTEMPTS
                    : VerificationResult.INVALID_CODE;
        }

        if (users.existsByEmailIgnoreCase(email)) {
            pendingRegistrations.deleteByEmailIgnoreCase(email);
            return VerificationResult.ALREADY_VERIFIED;
        }

        User user = new User();
        user.setFullName(pending.getFullName());
        user.setEmail(pending.getEmail());
        user.setPassword(pending.getPasswordHash());
        user.setPhone(pending.getPhone());
        user.setAddress(pending.getAddress());
        user.setRole("ROLE_USER");
        users.saveAndFlush(user);
        pendingRegistrations.deleteByEmailIgnoreCase(email);
        return VerificationResult.SUCCESS;
    }

    public ResendResponse resend(String emailValue, String requestTokenValue, String baseUrl) {
        String email = normalizeEmail(emailValue);
        String requestToken = normalizeRequestToken(requestTokenValue);
        if (email.isBlank()) return new ResendResponse(ResendResult.NOT_FOUND, requestToken);
        if (baseUrl == null || baseUrl.isBlank()) {
            return new ResendResponse(ResendResult.PUBLIC_URL_UNAVAILABLE, requestToken);
        }

        ResendPreparation preparation = transactionTemplate.execute(status -> {
            if (users.existsByEmailIgnoreCase(email)) {
                return new ResendPreparation(
                        ResendResult.ALREADY_VERIFIED, null, null, requestToken, null, null);
            }

            Optional<PendingRegistration> optional = resolvePendingWithLock(email, requestToken);
            if (optional.isEmpty()) {
                return new ResendPreparation(
                        ResendResult.NOT_FOUND, null, null, requestToken, null, null);
            }

            PendingRegistration current = optional.get();
            PendingRegistration replacement = cloneRegistration(current);
            LocalDateTime now = LocalDateTime.now();
            replacement.setRegistrationToken(generateRequestToken());
            replacement.setCreatedAt(now);
            String code = generateCode();
            applyNewCode(replacement, code, now);
            pendingRegistrations.saveAndFlush(replacement);

            String verificationUrl = buildVerificationUrl(
                    baseUrl, email, replacement.getRegistrationToken(), code);
            MailRequest mail = new MailRequest(email, replacement.getFullName(), code, verificationUrl);
            return new ResendPreparation(
                    ResendResult.SENT,
                    current.getId(),
                    replacement.getId(),
                    current.getRegistrationToken(),
                    replacement.getRegistrationToken(),
                    mail);
        });

        if (preparation == null) {
            return new ResendResponse(ResendResult.EMAIL_DELIVERY_FAILED, requestToken);
        }
        if (preparation.result() != ResendResult.SENT) {
            return new ResendResponse(preparation.result(), preparation.currentToken());
        }

        if (!sendVerificationEmail(preparation.mail())) {
            deletePendingById(preparation.replacementId());
            return new ResendResponse(
                    ResendResult.EMAIL_DELIVERY_FAILED, preparation.currentToken());
        }

        ResendResult finalized = transactionTemplate.execute(status -> {
            if (users.existsByEmailIgnoreCase(email)) {
                pendingRegistrations.deleteById(preparation.replacementId());
                return ResendResult.ALREADY_VERIFIED;
            }
            if (!pendingRegistrations.existsById(preparation.replacementId())) {
                return ResendResult.NOT_FOUND;
            }
            pendingRegistrations.deleteOlderByEmailIgnoreCase(
                    email, preparation.replacementId());
            return ResendResult.SENT;
        });

        if (finalized == null) {
            return new ResendResponse(
                    ResendResult.EMAIL_DELIVERY_FAILED, preparation.currentToken());
        }
        return new ResendResponse(
                finalized,
                finalized == ResendResult.SENT
                        ? preparation.replacementToken()
                        : preparation.currentToken());
    }

    @Transactional(readOnly = true)
    public boolean hasPendingRegistration(String emailValue, String requestTokenValue) {
        String email = normalizeEmail(emailValue);
        String requestToken = normalizeRequestToken(requestTokenValue);
        if (email.isBlank()) return false;

        if (requestToken != null) {
            return pendingRegistrations.findFirstByEmailIgnoreCaseOrderByIdDesc(email)
                    .filter(pending -> pending.getRegistrationToken().equals(requestToken))
                    .isPresent();
        }

        // Compatibility for a single pending request created before request tokens were introduced.
        return pendingRegistrations.findAllByEmailIgnoreCase(email).size() == 1;
    }

    private Optional<PendingRegistration> resolvePendingWithLock(String email, String requestToken) {
        List<PendingRegistration> matches =
                pendingRegistrations.findAllByEmailIgnoreCaseWithLock(email);
        if (matches.isEmpty()) return Optional.empty();

        PendingRegistration latest = matches.getFirst();
        if (requestToken != null) {
            return latest.getRegistrationToken().equals(requestToken)
                    ? Optional.of(latest)
                    : Optional.empty();
        }

        return matches.size() == 1 ? Optional.of(latest) : Optional.empty();
    }

    private PendingRegistration cloneRegistration(PendingRegistration source) {
        PendingRegistration copy = new PendingRegistration();
        copy.setFullName(source.getFullName());
        copy.setEmail(source.getEmail());
        copy.setPhone(source.getPhone());
        copy.setAddress(source.getAddress());
        copy.setPasswordHash(source.getPasswordHash());
        return copy;
    }

    private String buildVerificationUrl(String baseUrl, String email,
                                        String requestToken, String code) {
        return UriComponentsBuilder.fromUriString(baseUrl.replaceAll("/+$", ""))
                .path("/verify-email")
                .queryParam("email", email)
                .queryParam("request", requestToken)
                .queryParam("code", code)
                .build()
                .encode()
                .toUriString();
    }

    private void applyNewCode(PendingRegistration pending, String code, LocalDateTime now) {
        pending.setVerificationCodeHash(passwordEncoder.encode(code));
        pending.setExpiresAt(now.plusMinutes(expiryMinutes));
        pending.setLastSentAt(now);
        pending.setFailedAttempts(0);
    }

    private void deletePendingById(Long pendingId) {
        if (pendingId == null) return;
        transactionTemplate.executeWithoutResult(status -> {
            if (pendingRegistrations.existsById(pendingId)) {
                pendingRegistrations.deleteById(pendingId);
            }
        });
    }

    private void deleteOlderPendingRegistrations(String email, Long latestId) {
        if (email == null || email.isBlank() || latestId == null) return;
        transactionTemplate.executeWithoutResult(status ->
                pendingRegistrations.deleteOlderByEmailIgnoreCase(email, latestId));
    }

    private String generateCode() {
        return String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
    }

    private String generateRequestToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRequestToken(String requestToken) {
        if (requestToken == null) return null;
        String normalized = requestToken.trim();
        return normalized.matches("[A-Za-z0-9_-]{20,64}") ? normalized : null;
    }

    private String normalizeCode(String code) {
        if (code == null) return null;
        String normalized = code.replaceAll("\\s+", "");
        return normalized.matches("\\d{6}") ? normalized : null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean sendVerificationEmail(MailRequest request) {
        if (request == null) return false;
        if (mailSender == null || mailUsername.isBlank()) {
            return logLocalCode(request);
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailUsername);
        message.setTo(request.recipientEmail());
        message.setSubject("Mã xác thực tài khoản LSHOE");
        message.setText("Xin chào " + request.recipientName() + ",\n\n"
                + "Mã xác thực tài khoản LSHOE của bạn là:\n\n"
                + request.code() + "\n\n"
                + "Mã có hiệu lực trong " + expiryMinutes + " phút.\n"
                + "Bạn cũng có thể mở liên kết sau để điền sẵn mã xác thực:\n"
                + request.verificationUrl() + "\n\n"
                + "Nếu bạn không đăng ký tài khoản này, hãy bỏ qua email.");

        for (int attempt = 1; attempt <= maxSendAttempts; attempt++) {
            try {
                mailSender.send(message);
                return true;
            } catch (MailException exception) {
                log.warn("Verification email attempt {}/{} failed for {}",
                        attempt, maxSendAttempts, request.recipientEmail(), exception);
            }
        }
        return logLocalCode(request);
    }

    private boolean logLocalCode(MailRequest request) {
        if (allowLocalCodeLogging) {
            log.warn("LOCAL DEVELOPMENT verification for {}: code={}, link={}",
                    request.recipientEmail(), request.code(), request.verificationUrl());
            return true;
        }
        log.error("Verification email was not delivered to {}", request.recipientEmail());
        return false;
    }

    private record MailRequest(String recipientEmail, String recipientName,
                               String code, String verificationUrl) {}

    private record RegistrationPreparation(StartResult result, Long pendingId,
                                           String requestToken, MailRequest mail) {}

    private record ResendPreparation(ResendResult result,
                                     Long currentId,
                                     Long replacementId,
                                     String currentToken,
                                     String replacementToken,
                                     MailRequest mail) {}

    public record StartResponse(StartResult result, String requestToken) {}

    public record ResendResponse(ResendResult result, String requestToken) {}

    public enum StartResult {
        STARTED,
        EMAIL_ALREADY_USED,
        PUBLIC_URL_UNAVAILABLE,
        EMAIL_DELIVERY_FAILED
    }

    public enum VerificationResult {
        SUCCESS,
        INVALID_CODE,
        EXPIRED,
        TOO_MANY_ATTEMPTS,
        NOT_FOUND,
        ALREADY_VERIFIED
    }

    public enum ResendResult {
        SENT,
        NOT_FOUND,
        ALREADY_VERIFIED,
        PUBLIC_URL_UNAVAILABLE,
        EMAIL_DELIVERY_FAILED
    }
}
