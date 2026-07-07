package com.example.lshoestore.controller;

import com.example.lshoestore.service.CartService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/cart")
public class CartController {

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping
    public String view(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("items", cart.getItems(auth, session));
        model.addAttribute("total", cart.total(auth, session));
        model.addAttribute("cartCount", cart.count(auth, session));
        return "cart/view";
    }

    @PostMapping("/add/{id}")
    public String add(@PathVariable Long id, Authentication auth, HttpSession session, RedirectAttributes redirectAttrs) {
        boolean added = cart.add(id, auth, session);
        if (!added) {
            redirectAttrs.addFlashAttribute("error", "Sản phẩm đã hết hàng hoặc không khả dụng.");
        }
        return "redirect:/cart";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id, @RequestParam int quantity, Authentication auth, HttpSession session) {
        cart.update(id, quantity, auth, session);
        return "redirect:/cart";
    }

    @PostMapping("/remove/{id}")
    public String remove(@PathVariable Long id, Authentication auth, HttpSession session) {
        cart.remove(id, auth, session);
        return "redirect:/cart";
    }
}
