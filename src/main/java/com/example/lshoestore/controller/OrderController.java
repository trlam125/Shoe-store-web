package com.example.lshoestore.controller;

import com.example.lshoestore.model.*;
import com.example.lshoestore.repository.*;
import com.example.lshoestore.service.CartService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Controller
public class OrderController {
    private final CartService cart;
    private final UserRepository users;
    private final OrderRepository orders;

    public OrderController(CartService cart, UserRepository users, OrderRepository orders) {
        this.cart = cart;
        this.users = users;
        this.orders = orders;
    }

    @GetMapping("/checkout")
    public String checkout(Model model, Authentication auth) {
        if (cart.isEmpty()) return "redirect:/cart";
        User u = users.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("user", u);
        model.addAttribute("items", cart.getItems());
        model.addAttribute("total", cart.total());
        model.addAttribute("cartCount", cart.count());
        return "order/checkout";
    }

    @PostMapping("/checkout")
    public String place(Authentication auth, @RequestParam String receiverName, @RequestParam String phone, @RequestParam String address) {
        User u = users.findByEmail(auth.getName()).orElseThrow();
        Order o = new Order();
        o.setUser(u);
        o.setReceiverName(receiverName);
        o.setPhone(phone);
        o.setAddress(address);
        o.setTotal(cart.total());
        for (CartItem ci : cart.getItems()) {
            OrderItem it = new OrderItem();
            it.setOrder(o);
            it.setProduct(ci.getProduct());
            it.setQuantity(ci.getQuantity());
            it.setPrice(ci.getProduct().getPrice());
            o.getItems().add(it);
        }
        orders.save(o);
        cart.clear();
        return "redirect:/orders";
    }

    @GetMapping("/orders")
    public String myOrders(Authentication auth, Model model) {
        User u = users.findByEmail(auth.getName()).orElseThrow();
        model.addAttribute("orders", orders.findByUserOrderByCreatedAtDesc(u));
        return "order/list";
    }
}
