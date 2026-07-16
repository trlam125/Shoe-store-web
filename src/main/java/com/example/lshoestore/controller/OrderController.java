package com.example.lshoestore.controller;

import com.example.lshoestore.model.CartItem;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.OrderRepository;
import com.example.lshoestore.repository.UserRepository;
import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.OrderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    private boolean isLoggedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
    }

    @GetMapping("/checkout")
    public String checkout(Model model, Authentication auth, HttpSession session) {
        if (!isLoggedIn(auth)) return "redirect:/login";
        if (cart.isEmpty(auth, session)) return "redirect:/cart";

        User user = users.findByEmailIgnoreCase(auth.getName()).orElseThrow();
        model.addAttribute("user", user);
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
        if (!isLoggedIn(auth)) return "redirect:/login";

        if (cart.isEmpty(auth, session)) {
            redirectAttrs.addFlashAttribute("error", "Giỏ hàng của bạn đang trống.");
            return "redirect:/cart";
        }
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

        User user = users.findByEmailIgnoreCase(auth.getName()).orElseThrow();
        Collection<CartItem> items = cart.getItems(auth, session);

        try {
            orderService.placeOrder(
                    user,
                    receiverName.trim(),
                    phone.trim(),
                    address.trim(),
                    items,
                    auth,
                    session
            );
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            if (message != null && message.contains("|")) {
                message = message.split("\\|")[0];
            }
            redirectAttrs.addFlashAttribute("error", message != null ? message : "Không thể tạo đơn hàng.");
            return "redirect:/cart";
        }

        return "redirect:/orders";
    }

    @GetMapping("/orders")
    public String myOrders(Authentication auth, Model model, HttpSession session) {
        if (!isLoggedIn(auth)) return "redirect:/login";

        User user = users.findByEmailIgnoreCase(auth.getName()).orElseThrow();
        model.addAttribute("orders", orders.findByUserOrderByCreatedAtDesc(user));
        model.addAttribute("cartCount", cart.count(auth, session));
        return "order/list";
    }
}
