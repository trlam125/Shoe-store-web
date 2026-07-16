package com.example.lshoestore.controller;

import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.PasswordResetService;
import jakarta.servlet.http.HttpSession;
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

    public PasswordResetController(PasswordResetService passwordResetService, CartService cart) {
        this.passwordResetService = passwordResetService;
        this.cart = cart;
    }

    @GetMapping("/forgot-password")
    public String forgotPassword(Model model, Authentication auth, HttpSession session) {
        addCartCount(model, auth, session);
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String requestReset(@RequestParam(required = false) String email,
                               Model model, Authentication auth, HttpSession session) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        passwordResetService.requestReset(email, baseUrl);
        addCartCount(model, auth, session);
        model.addAttribute("success",
                "Nếu email tồn tại, hướng dẫn đặt lại mật khẩu đã được gửi.");
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
        boolean tokenValid = passwordResetService.isValid(token);
        model.addAttribute("tokenValid", tokenValid);

        if (!tokenValid) {
            model.addAttribute("error", "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.");
            return "auth/reset-password";
        }

        if (password == null || password.length() < 6) {
            model.addAttribute("error", "Mật khẩu phải có ít nhất 6 ký tự.");
            return "auth/reset-password";
        }
        if (!password.equals(confirmPassword)) {
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
