package com.example.lshoestore.controller;

import com.example.lshoestore.dto.CheckoutForm;
import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.exception.ResourceNotFoundException;
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
        if (cart.isEmpty(auth, session)) return "redirect:/cart";

        CheckoutForm form = new CheckoutForm();
        form.setReceiverName(user.getFullName());
        form.setPhone(user.getPhone());
        form.setAddress(user.getAddress());
        form.setCheckoutToken(UUID.randomUUID().toString());
        model.addAttribute("checkoutForm", form);
        addCheckoutData(model, auth, session);
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
            addCheckoutData(model, auth, session);
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

    private void addCheckoutData(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("items", cart.getItems(auth, session));
        model.addAttribute("total", cart.total(auth, session));
        model.addAttribute("cartCount", cart.count(auth, session));
    }
}
