package com.example.lshoestore.controller;

import com.example.lshoestore.service.CartService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/cart")
public class CartController {
    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping
    public String view(Model model) {
        model.addAttribute("items", cart.getItems());
        model.addAttribute("total", cart.total());
        model.addAttribute("cartCount", cart.count());
        return "cart/view";
    }

    @PostMapping("/add/{id}")
    public String add(@PathVariable Long id) {
        cart.add(id);
        return "redirect:/cart";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id, @RequestParam int quantity) {
        cart.update(id, quantity);
        return "redirect:/cart";
    }

    @PostMapping("/remove/{id}")
    public String remove(@PathVariable Long id) {
        cart.remove(id);
        return "redirect:/cart";
    }
}
