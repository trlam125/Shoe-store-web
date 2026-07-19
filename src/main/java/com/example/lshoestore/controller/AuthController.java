package com.example.lshoestore.controller;

import com.example.lshoestore.dto.RegistrationForm;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.UserRepository;
import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.PasswordPolicy;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
        model.addAttribute("form", new RegistrationForm());
        model.addAttribute("cartCount", cart.count(auth, session));
        prepareCaptcha(model, session);
        return "auth/register";
    }

    @PostMapping("/register")
    public String doRegister(@Valid @ModelAttribute("form") RegistrationForm form,
                             BindingResult bindingResult,
                             Model model,
                             @RequestParam(required = false) String captchaAnswer,
                             Authentication auth,
                             HttpSession session) {
        model.addAttribute("cartCount", cart.count(auth, session));
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

        String email = form.getEmail().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            form.setPassword("");
            model.addAttribute("error", "Email đã được sử dụng.");
            prepareCaptcha(model, session);
            return "auth/register";
        }

        User user = new User();
        user.setFullName(form.getFullName().trim());
        user.setEmail(email);
        user.setPhone(blankToNull(form.getPhone()));
        user.setAddress(blankToNull(form.getAddress()));
        user.setPassword(encoder.encode(form.getPassword()));
        user.setRole("ROLE_USER");
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            form.setPassword("");
            model.addAttribute("error", users.existsByEmailIgnoreCase(email)
                    ? "Email đã được sử dụng."
                    : "Không thể tạo tài khoản. Vui lòng kiểm tra lại thông tin.");
            prepareCaptcha(model, session);
            return "auth/register";
        }
        return "redirect:/login?registered";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
