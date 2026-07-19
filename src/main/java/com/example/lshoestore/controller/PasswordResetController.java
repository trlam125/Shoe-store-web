package com.example.lshoestore.controller;

import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.PasswordResetService;
import com.example.lshoestore.service.PasswordPolicy;
import com.example.lshoestore.service.RequestRateLimiter;
import com.example.lshoestore.service.PublicBaseUrlResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.Locale;

@Controller
public class PasswordResetController {
    private final PasswordResetService passwordResetService;
    private final CartService cart;
    private final RequestRateLimiter rateLimiter;
    private final PublicBaseUrlResolver baseUrlResolver;
    private final int resetRequestsPerWindow;

    public PasswordResetController(PasswordResetService passwordResetService,
                                   CartService cart,
                                   RequestRateLimiter rateLimiter,
                                   PublicBaseUrlResolver baseUrlResolver,
                                   @Value("${app.rate-limit.password-reset-per-15-minutes:3}")
                                   int resetRequestsPerWindow) {
        this.passwordResetService = passwordResetService;
        this.cart = cart;
        this.rateLimiter = rateLimiter;
        this.baseUrlResolver = baseUrlResolver;
        this.resetRequestsPerWindow = Math.max(resetRequestsPerWindow, 1);
    }

    @GetMapping("/forgot-password")
    public String forgotPassword(Model model, Authentication auth, HttpSession session) {
        addCartCount(model, auth, session);
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String requestReset(@RequestParam(required = false) String email,
                               HttpServletRequest request,
                               Model model, Authentication auth, HttpSession session) {
        String baseUrl = baseUrlResolver.resolve(request);
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        boolean ipAllowed = rateLimiter.allowIp(
                "password-reset", request, resetRequestsPerWindow, Duration.ofMinutes(15));
        boolean emailAllowed = normalizedEmail.isBlank() || rateLimiter.allowIdentity(
                "password-reset-email", normalizedEmail,
                resetRequestsPerWindow, Duration.ofMinutes(15));
        if (baseUrl != null && ipAllowed && emailAllowed) {
            passwordResetService.requestReset(email, baseUrl);
        }
        addCartCount(model, auth, session);
        model.addAttribute("success",
                "Yêu cầu đã được ghi nhận. Nếu email tồn tại và hệ thống gửi thư thành công, "
                        + "bạn sẽ nhận được hướng dẫn đặt lại mật khẩu.");
        return "auth/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam(required = false) String token,
                                Model model, Authentication auth, HttpSession session) {
        addCartCount(model, auth, session);
        model.addAttribute("token", token);
        model.addAttribute("tokenValid", passwordResetService.isValid(token));
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String updatePassword(@RequestParam String token,
                                 @RequestParam String password,
                                 @RequestParam String confirmPassword,
                                 Model model, Authentication auth, HttpSession session) {
        addCartCount(model, auth, session);
        model.addAttribute("token", token);
        if (!PasswordPolicy.isValidForBcrypt(password)) {
            model.addAttribute("tokenValid", passwordResetService.isValid(token));
            model.addAttribute("error", PasswordPolicy.VALIDATION_MESSAGE);
            return "auth/reset-password";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("tokenValid", passwordResetService.isValid(token));
            model.addAttribute("error", "Mật khẩu xác nhận không khớp.");
            return "auth/reset-password";
        }
        if (!passwordResetService.resetPassword(token, password)) {
            model.addAttribute("tokenValid", false);
            model.addAttribute("error", "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.");
            return "auth/reset-password";
        }
        return "redirect:/login?reset";
    }

    private void addCartCount(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("cartCount", cart.count(auth, session));
    }
}
