package com.example.lshoestore.controller;

import com.example.lshoestore.service.CartService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/cart")
public class CartController {
    private final CartService cart;

    public CartController(CartService cart) { this.cart = cart; }

    @GetMapping
    public String view(Model model, Authentication auth, HttpSession session) {
        var items = cart.getItems(auth, session);
        model.addAttribute("items", items);
        model.addAttribute("total", items.stream()
                .map(item -> item.getProduct().getPrice()
                        .multiply(java.math.BigDecimal.valueOf(item.getQuantity())))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        model.addAttribute("cartCount", items.stream().mapToInt(item -> item.getQuantity()).sum());
        return "cart/view";
    }

    @PostMapping("/add/{id}")
    public String add(@PathVariable Long id, Authentication auth, HttpSession session,
                      RedirectAttributes redirectAttributes) {
        if (!cart.add(id, auth, session)) {
            redirectAttributes.addFlashAttribute("error", "Sản phẩm đã hết hàng hoặc không khả dụng.");
        } else {
            redirectAttributes.addFlashAttribute("success", "Đã thêm sản phẩm vào giỏ hàng.");
        }
        return "redirect:/cart";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id, @RequestParam int quantity,
                         Authentication auth, HttpSession session,
                         RedirectAttributes redirectAttributes) {
        cart.update(id, quantity, auth, session);
        redirectAttributes.addFlashAttribute("success", "Đã cập nhật giỏ hàng.");
        return "redirect:/cart";
    }

    @PostMapping("/remove/{id}")
    public String remove(@PathVariable Long id, Authentication auth, HttpSession session,
                         RedirectAttributes redirectAttributes) {
        cart.remove(id, auth, session);
        redirectAttributes.addFlashAttribute("success", "Đã xóa sản phẩm khỏi giỏ hàng.");
        return "redirect:/cart";
    }
}
