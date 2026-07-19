package com.example.lshoestore.controller;

import com.example.lshoestore.dto.RegistrationForm;
import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.EmailVerificationService;
import com.example.lshoestore.service.PasswordPolicy;
import com.example.lshoestore.service.PublicBaseUrlResolver;
import com.example.lshoestore.service.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

@Controller
public class AuthController {
    private static final String CAPTCHA_ANSWER = "REGISTER_CAPTCHA_ANSWER";

    private final EmailVerificationService emailVerificationService;
    private final PublicBaseUrlResolver baseUrlResolver;
    private final RequestRateLimiter rateLimiter;
    private final CartService cart;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int registrationRequestsPerWindow;
    private final int resendRequestsPerWindow;
    private final int verificationAttemptsPerWindow;

    public AuthController(EmailVerificationService emailVerificationService,
                          PublicBaseUrlResolver baseUrlResolver,
                          RequestRateLimiter rateLimiter,
                          CartService cart,
                          @Value("${app.rate-limit.registration-email-per-15-minutes:5}")
                          int registrationRequestsPerWindow,
                          @Value("${app.rate-limit.verification-resend-per-15-minutes:3}")
                          int resendRequestsPerWindow,
                          @Value("${app.rate-limit.verification-attempts-per-15-minutes:20}")
                          int verificationAttemptsPerWindow) {
        this.emailVerificationService = emailVerificationService;
        this.baseUrlResolver = baseUrlResolver;
        this.rateLimiter = rateLimiter;
        this.cart = cart;
        this.registrationRequestsPerWindow = Math.max(registrationRequestsPerWindow, 1);
        this.resendRequestsPerWindow = Math.max(resendRequestsPerWindow, 1);
        this.verificationAttemptsPerWindow = Math.max(verificationAttemptsPerWindow, 1);
    }

    @GetMapping("/login")
    public String login(Model model, Authentication auth, HttpSession session) {
        addCartCount(model, auth, session);
        return "auth/login";
    }

    @GetMapping("/register")
    public String register(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("form", new RegistrationForm());
        addCartCount(model, auth, session);
        prepareCaptcha(model, session);
        return "auth/register";
    }

    @PostMapping("/register")
    public String doRegister(@Valid @ModelAttribute("form") RegistrationForm form,
                             BindingResult bindingResult,
                             Model model,
                             @RequestParam(required = false) String captchaAnswer,
                             HttpServletRequest request,
                             Authentication auth,
                             HttpSession session) {
        addCartCount(model, auth, session);
        boolean captchaValid = verifyCaptcha(captchaAnswer, session);
        if (!captchaValid) model.addAttribute("captchaError", "Kết quả xác minh không đúng.");
        if (!PasswordPolicy.isValidForBcrypt(form.getPassword())) {
            bindingResult.rejectValue("password", "password.bytes", PasswordPolicy.VALIDATION_MESSAGE);
        }
        if (bindingResult.hasErrors() || !captchaValid) {
            form.setPassword("");
            prepareCaptcha(model, session);
            return "auth/register";
        }

        String email = normalizeEmail(form.getEmail());
        boolean ipAllowed = rateLimiter.allowIp(
                "registration-email", request, registrationRequestsPerWindow, Duration.ofMinutes(15));
        boolean emailAllowed = rateLimiter.allowIdentity(
                "registration-email-address", email, registrationRequestsPerWindow, Duration.ofMinutes(15));
        if (!ipAllowed || !emailAllowed) {
            form.setPassword("");
            model.addAttribute("error", "Bạn đã yêu cầu gửi mã quá nhiều lần. Vui lòng thử lại sau.");
            prepareCaptcha(model, session);
            return "auth/register";
        }

        EmailVerificationService.StartResponse response;
        try {
            response = emailVerificationService.startRegistration(form, baseUrlResolver.resolve(request));
        } catch (DataIntegrityViolationException exception) {
            form.setPassword("");
            model.addAttribute("error", "Không thể tạo yêu cầu đăng ký. Vui lòng thử lại.");
            prepareCaptcha(model, session);
            return "auth/register";
        }
        EmailVerificationService.StartResult result = response.result();
        if (result == EmailVerificationService.StartResult.EMAIL_ALREADY_USED) {
            form.setPassword("");
            model.addAttribute("error", "Email đã được sử dụng.");
            prepareCaptcha(model, session);
            return "auth/register";
        }
        if (result == EmailVerificationService.StartResult.PUBLIC_URL_UNAVAILABLE) {
            form.setPassword("");
            model.addAttribute("error", "Không xác định được địa chỉ website để tạo liên kết xác thực.");
            prepareCaptcha(model, session);
            return "auth/register";
        }
        if (result == EmailVerificationService.StartResult.EMAIL_DELIVERY_FAILED) {
            form.setPassword("");
            model.addAttribute("error",
                    "Hệ thống chưa gửi được email xác thực và đã hủy yêu cầu chưa gửi. "
                            + "Vui lòng kiểm tra cấu hình email hoặc thử lại sau.");
            prepareCaptcha(model, session);
            return "auth/register";
        }

        String target = UriComponentsBuilder.fromPath("/verify-email")
                .queryParam("email", email)
                .queryParam("request", response.requestToken())
                .queryParam("sent", true)
                .build()
                .encode()
                .toUriString();
        return "redirect:" + target;
    }

    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam(required = false) String email,
                              @RequestParam(name = "request", required = false) String requestToken,
                              @RequestParam(required = false) String code,
                              Model model,
                              Authentication auth,
                              HttpSession session) {
        String normalizedEmail = normalizeEmail(email);
        addCartCount(model, auth, session);
        model.addAttribute("email", normalizedEmail);
        model.addAttribute("requestToken", requestToken == null ? "" : requestToken.trim());
        model.addAttribute("code", code == null ? "" : code.trim());
        model.addAttribute("pending",
                emailVerificationService.hasPendingRegistration(normalizedEmail, requestToken));
        return "auth/verify-email";
    }

    @PostMapping("/verify-email")
    public String completeEmailVerification(@RequestParam String email,
                                            @RequestParam(name = "request", required = false)
                                            String requestToken,
                                            @RequestParam String code,
                                            HttpServletRequest request,
                                            Model model,
                                            Authentication auth,
                                            HttpSession session) {
        String normalizedEmail = normalizeEmail(email);
        addCartCount(model, auth, session);
        model.addAttribute("email", normalizedEmail);
        model.addAttribute("requestToken", requestToken == null ? "" : requestToken.trim());
        model.addAttribute("code", code == null ? "" : code.trim());

        boolean ipAllowed = rateLimiter.allowIp(
                "verification-attempt", request, verificationAttemptsPerWindow, Duration.ofMinutes(15));
        boolean emailAllowed = rateLimiter.allowIdentity(
                "verification-attempt-email", normalizedEmail,
                verificationAttemptsPerWindow, Duration.ofMinutes(15));
        if (!ipAllowed || !emailAllowed) {
            model.addAttribute("pending", true);
            model.addAttribute("error", "Bạn đã nhập mã quá nhiều lần. Vui lòng thử lại sau.");
            return "auth/verify-email";
        }

        EmailVerificationService.VerificationResult result;
        try {
            result = emailVerificationService.verify(normalizedEmail, requestToken, code);
        } catch (DataIntegrityViolationException exception) {
            // A concurrent verification may have inserted the same email first. Only
            // report success after the committed user row can actually be observed.
            if (emailVerificationService.isRegisteredEmail(normalizedEmail)) {
                return "redirect:/login?verified";
            }
            boolean pending = emailVerificationService.hasPendingRegistration(
                    normalizedEmail, requestToken);
            return verificationError(model, pending,
                    "Không thể hoàn tất xác thực do lỗi dữ liệu. Vui lòng thử lại.");
        }
        return switch (result) {
            case SUCCESS, ALREADY_VERIFIED -> "redirect:/login?verified";
            case INVALID_CODE -> verificationError(model, true, "Mã xác thực không đúng.");
            case EXPIRED -> verificationError(model, true, "Mã xác thực đã hết hạn. Hãy gửi lại mã mới.");
            case TOO_MANY_ATTEMPTS -> verificationError(
                    model, true, "Bạn đã nhập sai quá nhiều lần. Hãy gửi lại mã mới.");
            case NOT_FOUND -> verificationError(
                    model, false, "Không tìm thấy yêu cầu đăng ký. Vui lòng đăng ký lại.");
        };
    }

    @PostMapping("/verify-email/resend")
    public String resendVerificationCode(@RequestParam String email,
                                         @RequestParam(name = "request", required = false)
                                         String requestToken,
                                         HttpServletRequest request,
                                         Model model,
                                         Authentication auth,
                                         HttpSession session) {
        String normalizedEmail = normalizeEmail(email);
        addCartCount(model, auth, session);
        model.addAttribute("email", normalizedEmail);
        model.addAttribute("requestToken", requestToken == null ? "" : requestToken.trim());
        model.addAttribute("code", "");

        boolean ipAllowed = rateLimiter.allowIp(
                "verification-resend", request, resendRequestsPerWindow, Duration.ofMinutes(15));
        boolean emailAllowed = rateLimiter.allowIdentity(
                "verification-resend-email", normalizedEmail,
                resendRequestsPerWindow, Duration.ofMinutes(15));
        if (!ipAllowed || !emailAllowed) {
            model.addAttribute("pending", true);
            model.addAttribute("error", "Bạn đã gửi lại mã quá nhiều lần. Vui lòng thử lại sau.");
            return "auth/verify-email";
        }

        EmailVerificationService.ResendResponse response = emailVerificationService.resend(
                normalizedEmail, requestToken, baseUrlResolver.resolve(request));
        model.addAttribute("requestToken",
                response.requestToken() == null ? "" : response.requestToken());
        EmailVerificationService.ResendResult result = response.result();
        return switch (result) {
            case SENT -> verificationSuccess(model, true, "Mã xác thực mới đã được gửi tới email của bạn.");
            case ALREADY_VERIFIED -> "redirect:/login?verified";
            case NOT_FOUND -> verificationError(
                    model, false, "Không tìm thấy yêu cầu đăng ký. Vui lòng đăng ký lại.");
            case PUBLIC_URL_UNAVAILABLE -> verificationError(
                    model, true, "Không xác định được địa chỉ website để tạo liên kết xác thực.");
            case EMAIL_DELIVERY_FAILED -> verificationError(
                    model, true, "Không gửi được email xác thực. Vui lòng kiểm tra cấu hình email hoặc thử lại sau.");
        };
    }

    private String verificationError(Model model, boolean pending, String message) {
        model.addAttribute("pending", pending);
        model.addAttribute("error", message);
        return "auth/verify-email";
    }

    private String verificationSuccess(Model model, boolean pending, String message) {
        model.addAttribute("pending", pending);
        model.addAttribute("success", message);
        return "auth/verify-email";
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private void addCartCount(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("cartCount", cart.count(auth, session));
    }

    private void prepareCaptcha(Model model, HttpSession session) {
        int left = secureRandom.nextInt(9) + 1;
        int right = secureRandom.nextInt(9) + 1;
        session.setAttribute(CAPTCHA_ANSWER, left + right);
        model.addAttribute("captchaQuestion", left + " + " + right + " = ?");
    }

    private boolean verifyCaptcha(String answer, HttpSession session) {
        Object expected = session.getAttribute(CAPTCHA_ANSWER);
        session.removeAttribute(CAPTCHA_ANSWER);
        return expected != null && answer != null && String.valueOf(expected).equals(answer.trim());
    }
}
