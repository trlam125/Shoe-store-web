package com.example.lshoestore.controller;

import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@Controller
public class AuthController {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AuthController(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }

    @GetMapping("/register")
    public String register(Model model) {
        model.addAttribute("user", new User());
        return "auth/register";
    }

    @PostMapping("/register")
    public String doRegister(@Valid @ModelAttribute User user, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            return "auth/register";
        }

        // Registration must always create a new regular user, regardless of extra request fields.
        user.setId(null);
        user.setFullName(user.getFullName().trim());
        user.setEmail(user.getEmail().trim().toLowerCase(Locale.ROOT));

        if (users.existsByEmailIgnoreCase(user.getEmail())) {
            model.addAttribute("error", "Email đã được sử dụng.");
            return "auth/register";
        }
        user.setPassword(encoder.encode(user.getPassword()));
        user.setRole("ROLE_USER");
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            user.setPassword("");
            model.addAttribute("error", "Email đã được sử dụng.");
            return "auth/register";
        }
        return "redirect:/login?registered";
    }
}
