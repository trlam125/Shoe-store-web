package com.example.lshoestore.controller;

import com.example.lshoestore.dto.CheckoutForm;
import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.exception.ResourceNotFoundException;
import com.example.lshoestore.model.CartItem;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.OrderRepository;
import com.example.lshoestore.repository.UserRepository;
import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.OrderService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
        User user = currentUser(auth);
        List<CartItem> items = cart.getItems(auth, session);
        if (items.isEmpty()) return "redirect:/cart";

        CheckoutForm form = new CheckoutForm();
        form.setReceiverName(user.getFullName());
        form.setPhone(user.getPhone());
        form.setAddress(user.getAddress());
        form.setCheckoutToken(UUID.randomUUID().toString());
        model.addAttribute("checkoutForm", form);
        addCheckoutData(model, items);
        return "order/checkout";
    }

    @PostMapping("/checkout")
    public String place(@Valid @ModelAttribute("checkoutForm") CheckoutForm form,
                        BindingResult bindingResult,
                        Authentication auth,
                        HttpSession session,
                        Model model,
                        RedirectAttributes redirectAttributes) {
        User user = currentUser(auth);
        if (bindingResult.hasErrors()) {
            List<CartItem> items = cart.getItems(auth, session);
            if (items.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Giỏ hàng của bạn đang trống.");
                return "redirect:/cart";
            }
            addCheckoutData(model, items);
            return "order/checkout";
        }

        try {
            orderService.placeOrder(user, form);
            redirectAttributes.addFlashAttribute("success", "Đơn hàng đã được tạo thành công.");
            return "redirect:/orders";
        } catch (BusinessException exception) {
            if ("duplicate_checkout".equals(exception.getCode())) {
                redirectAttributes.addFlashAttribute("warning", "Đơn hàng này đã được ghi nhận trước đó.");
                return "redirect:/orders";
            }
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
            return "redirect:/cart";
        } catch (DataIntegrityViolationException exception) {
            if (orders.existsByCheckoutToken(form.getCheckoutToken())) {
                redirectAttributes.addFlashAttribute("warning", "Đơn hàng đã được ghi nhận trước đó.");
                return "redirect:/orders";
            }
            redirectAttributes.addFlashAttribute("error", "Không thể tạo đơn hàng. Vui lòng kiểm tra lại thông tin và thử lại.");
            return "redirect:/cart";
        }
    }

    @GetMapping("/orders")
    public String myOrders(Authentication auth, Model model, HttpSession session) {
        User user = currentUser(auth);
        model.addAttribute("orders", orders.findByUserOrderByCreatedAtDesc(user));
        model.addAttribute("cartCount", cart.count(auth, session));
        return "order/list";
    }

    private User currentUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResourceNotFoundException("Không tìm thấy tài khoản người dùng");
        }
        return users.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản người dùng"));
    }

    private void addCheckoutData(Model model, List<CartItem> items) {
        BigDecimal total = items.stream()
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int cartCount = items.stream().mapToInt(CartItem::getQuantity).sum();
        model.addAttribute("items", items);
        model.addAttribute("total", total);
        model.addAttribute("cartCount", cartCount);
    }
}
