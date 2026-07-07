package com.example.lshoestore.controller;

import com.example.lshoestore.model.*;
import com.example.lshoestore.repository.*;
import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.OrderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collection;

@Controller
public class OrderController {

    private final CartService cart;
    private final UserRepository users;
    private final OrderRepository orders;
    private final OrderService orderService;

    public OrderController(CartService cart, UserRepository users,
                           OrderRepository orders, OrderService orderService) {
        this.cart = cart;
        this.users = users;
        this.orders = orders;
        this.orderService = orderService;
    }

    @GetMapping("/checkout")
    public String checkout(Model model, Authentication auth, HttpSession session) {
        if (auth == null || !auth.isAuthenticated()) return "redirect:/login";
        if (cart.isEmpty(auth, session)) return "redirect:/cart";
        User u = users.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("user", u);
        model.addAttribute("items", cart.getItems(auth, session));
        model.addAttribute("total", cart.total(auth, session));
        model.addAttribute("cartCount", cart.count(auth, session));
        return "order/checkout";
    }

    @PostMapping("/checkout")
    public String place(Authentication auth, HttpSession session,
                        @RequestParam String receiverName,
                        @RequestParam String phone,
                        @RequestParam String address,
                        RedirectAttributes redirectAttrs) {

        // Fix #8: guard giỏ hàng rỗng ở POST
        if (cart.isEmpty(auth, session)) {
            redirectAttrs.addFlashAttribute("error", "Giỏ hàng của bạn đang trống.");
            return "redirect:/cart";
        }

        // Validate input
        if (receiverName == null || receiverName.isBlank()) {
            redirectAttrs.addFlashAttribute("error", "Vui lòng nhập họ tên người nhận.");
            return "redirect:/checkout";
        }
        if (phone == null || !phone.matches("^(0|\\+84)[0-9]{8,10}$")) {
            redirectAttrs.addFlashAttribute("error", "Số điện thoại không hợp lệ (VD: 0901234567).");
            return "redirect:/checkout";
        }
        if (address == null || address.isBlank()) {
            redirectAttrs.addFlashAttribute("error", "Vui lòng nhập địa chỉ nhận hàng.");
            return "redirect:/checkout";
        }

        User u = users.findByEmail(auth.getName()).orElseThrow();
        Collection<CartItem> items = cart.getItems(auth, session);

        try {
            // Fix #5: cart.clear() được gọi bên trong transaction của orderService
            orderService.placeOrder(u, receiverName.trim(), phone.trim(), address.trim(), items, auth, session);
        } catch (IllegalStateException e) {
            String msg = e.getMessage().contains("|")
                    ? e.getMessage().split("\\|")[0]
                    : e.getMessage();
            redirectAttrs.addFlashAttribute("error", msg);
            return "redirect:/cart";
        }

        return "redirect:/orders";
    }

    @GetMapping("/orders")
    public String myOrders(Authentication auth, Model model, HttpSession session) {
        User u = users.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("orders", orders.findByUserOrderByCreatedAtDesc(u));
        model.addAttribute("cartCount", cart.count(auth, session));
        return "order/list";
    }
}
