package com.example.lshoestore.controller;

import com.example.lshoestore.service.CartService;
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
    public String view(Model model, Authentication auth) {
        model.addAttribute("items", cart.getItems(auth));
        model.addAttribute("total", cart.total(auth));
        model.addAttribute("cartCount", cart.count(auth));
        return "cart/view";
    }

    @PostMapping("/add/{id}")
    public String add(@PathVariable Long id, Authentication auth, RedirectAttributes redirectAttrs) {
        boolean added = cart.add(id, auth);
        if (!added) {
            redirectAttrs.addFlashAttribute("error", "Sản phẩm đã hết hàng hoặc không khả dụng.");
        }
        return "redirect:/cart";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id, @RequestParam int quantity, Authentication auth) {
        cart.update(id, quantity, auth);
        return "redirect:/cart";
    }

    @PostMapping("/remove/{id}")
    public String remove(@PathVariable Long id, Authentication auth) {
        cart.remove(id, auth);
        return "redirect:/cart";
    }
}
