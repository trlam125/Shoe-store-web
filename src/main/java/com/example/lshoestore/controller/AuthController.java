package com.example.lshoestore.controller;

import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.UserRepository;
import com.example.lshoestore.service.CartService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Locale;

@Controller
public class AuthController {
    private static final String CAPTCHA_ANSWER = "REGISTER_CAPTCHA_ANSWER";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final CartService cart;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthController(UserRepository users, PasswordEncoder encoder, CartService cart) {
        this.users = users;
        this.encoder = encoder;
        this.cart = cart;
    }

    @GetMapping("/login")
    public String login(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("cartCount", cart.count(auth, session));
        return "auth/login";
    }

    @GetMapping("/register")
    public String register(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("user", new User());
        model.addAttribute("cartCount", cart.count(auth, session));
        prepareCaptcha(model, session);
        return "auth/register";
    }

    @PostMapping("/register")
    public String doRegister(@Valid @ModelAttribute User user, BindingResult bindingResult, Model model,
                             @RequestParam(required = false) String captchaAnswer,
                             Authentication auth, HttpSession session) {
        model.addAttribute("cartCount", cart.count(auth, session));
        boolean captchaValid = verifyCaptcha(captchaAnswer, session);
        if (!captchaValid) {
            model.addAttribute("captchaError", "Kết quả xác minh không đúng. Vui lòng thử câu mới.");
        }
        if (bindingResult.hasErrors() || !captchaValid) {
            user.setPassword("");
            prepareCaptcha(model, session);
            return "auth/register";
        }

        // Registration must always create a new regular user, regardless of extra request fields.
        user.setId(null);
        user.setFullName(user.getFullName().trim());
        user.setEmail(user.getEmail().trim().toLowerCase(Locale.ROOT));

        if (users.existsByEmailIgnoreCase(user.getEmail())) {
            user.setPassword("");
            model.addAttribute("error", "Email đã được sử dụng.");
            prepareCaptcha(model, session);
            return "auth/register";
        }
        user.setPassword(encoder.encode(user.getPassword()));
        user.setRole("ROLE_USER");
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            user.setPassword("");
            model.addAttribute("error", "Email đã được sử dụng.");
            prepareCaptcha(model, session);
            return "auth/register";
        }
        return "redirect:/login?registered";
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
        if (expected == null || answer == null) {
            return false;
        }
        return String.valueOf(expected).equals(answer.trim());
    }
}
