package com.example.lshoestore.controller;

import com.example.lshoestore.dto.CheckoutForm;
import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.exception.ResourceNotFoundException;
import com.example.lshoestore.model.CartItem;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.OrderRepository;
import com.example.lshoestore.repository.UserRepository;
import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.CheckoutFingerprint;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class OrderController {
    private static final String CHECKOUT_TOKENS_SESSION_KEY = "checkoutTokens";
    private static final int MAX_PENDING_CHECKOUT_TOKENS = 5;

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
        String checkoutToken = UUID.randomUUID().toString();
        form.setCheckoutToken(checkoutToken);
        issueCheckoutToken(session, checkoutToken, CheckoutFingerprint.fromCartItems(items));
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
        String expectedFingerprint = getIssuedCheckoutFingerprint(session, form.getCheckoutToken());

        if (expectedFingerprint == null) {
            if (!bindingResult.hasFieldErrors("checkoutToken")
                    && orders.existsByCheckoutTokenAndUser(form.getCheckoutToken(), user)) {
                redirectAttributes.addFlashAttribute("warning", "Đơn hàng này đã được ghi nhận trước đó.");
                return "redirect:/orders";
            }
            redirectAttributes.addFlashAttribute("error", "Phiên thanh toán không hợp lệ hoặc đã hết hạn.");
            return "redirect:/cart";
        }

        if (bindingResult.hasErrors()) {
            List<CartItem> items = cart.getItems(auth, session);
            if (items.isEmpty()) {
                consumeCheckoutToken(session, form.getCheckoutToken());
                redirectAttributes.addFlashAttribute("error", "Giỏ hàng của bạn đang trống.");
                return "redirect:/cart";
            }
            if (!expectedFingerprint.equals(CheckoutFingerprint.fromCartItems(items))) {
                consumeCheckoutToken(session, form.getCheckoutToken());
                redirectAttributes.addFlashAttribute("error",
                        "Giỏ hàng hoặc thông tin sản phẩm đã thay đổi. Vui lòng kiểm tra và xác nhận lại.");
                return "redirect:/cart";
            }
            addCheckoutData(model, items);
            return "order/checkout";
        }

        // Consume before entering the database transaction so two concurrent submissions
        // from the same session cannot both be treated as fresh checkout attempts.
        consumeCheckoutToken(session, form.getCheckoutToken());

        try {
            orderService.placeOrder(user, form, expectedFingerprint);
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

    private void issueCheckoutToken(HttpSession session, String token, String fingerprint) {
        synchronized (session) {
            LinkedHashMap<String, String> tokens = readCheckoutTokens(session);
            tokens.put(token, fingerprint);
            while (tokens.size() > MAX_PENDING_CHECKOUT_TOKENS) {
                Iterator<String> iterator = tokens.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            session.setAttribute(CHECKOUT_TOKENS_SESSION_KEY, tokens);
        }
    }

    private String getIssuedCheckoutFingerprint(HttpSession session, String token) {
        if (token == null || token.isBlank()) return null;
        synchronized (session) {
            return readCheckoutTokens(session).get(token);
        }
    }

    private void consumeCheckoutToken(HttpSession session, String token) {
        if (token == null || token.isBlank()) return;
        synchronized (session) {
            LinkedHashMap<String, String> tokens = readCheckoutTokens(session);
            tokens.remove(token);
            if (tokens.isEmpty()) session.removeAttribute(CHECKOUT_TOKENS_SESSION_KEY);
            else session.setAttribute(CHECKOUT_TOKENS_SESSION_KEY, tokens);
        }
    }

    private LinkedHashMap<String, String> readCheckoutTokens(HttpSession session) {
        Object rawTokens = session.getAttribute(CHECKOUT_TOKENS_SESSION_KEY);
        LinkedHashMap<String, String> tokens = new LinkedHashMap<>();
        if (rawTokens instanceof Map<?, ?> storedTokens) {
            for (Map.Entry<?, ?> entry : storedTokens.entrySet()) {
                if (entry.getKey() instanceof String token && !token.isBlank()
                        && entry.getValue() instanceof String fingerprint && !fingerprint.isBlank()) {
                    tokens.put(token, fingerprint);
                }
            }
        }
        return tokens;
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
