package com.example.lshoestore.controller;

import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
public class PasswordResetController {
    private final PasswordResetService passwordResetService;
    private final CartService cart;
    private final String publicBaseUrl;
    private final boolean development;

    public PasswordResetController(PasswordResetService passwordResetService,
                                   CartService cart,
                                   @Value("${app.public-base-url:}") String publicBaseUrl,
                                   @Value("${app.environment:production}") String environment) {
        this.passwordResetService = passwordResetService;
        this.cart = cart;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        this.development = "development".equalsIgnoreCase(environment);
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
        String baseUrl = resolveBaseUrl(request);
        if (baseUrl != null) {
            passwordResetService.requestReset(email, baseUrl);
        }
        addCartCount(model, auth, session);
        model.addAttribute("success", "Nếu email tồn tại, hướng dẫn đặt lại mật khẩu đã được gửi.");
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
        if (password == null || password.length() < 8 || password.length() > 72) {
            model.addAttribute("tokenValid", passwordResetService.isValid(token));
            model.addAttribute("error", "Mật khẩu phải từ 8 đến 72 ký tự.");
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

    private String resolveBaseUrl(HttpServletRequest request) {
        if (!publicBaseUrl.isBlank()) return publicBaseUrl;
        if (!development || !isLocalRequest(request)) return null;
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        String serverName = request.getServerName();
        return "127.0.0.1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress)
                || "::1".equals(remoteAddress)
                || "localhost".equalsIgnoreCase(serverName)
                || "127.0.0.1".equals(serverName);
    }

    private void addCartCount(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("cartCount", cart.count(auth, session));
    }
}
