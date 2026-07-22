package com.example.lshoestore.controller;

import com.example.lshoestore.exception.BusinessException;
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
        model.addAttribute("cartCount", cart.count(auth, session));
        return "cart/view";
    }

    @PostMapping("/add/{id}")
    public String add(@PathVariable Long id,
                      @RequestParam(required = false) String selectedSize,
                      Authentication auth,
                      HttpSession session,
                      RedirectAttributes redirectAttributes) {
        try {
            if (!cart.add(id, selectedSize, auth, session)) {
                redirectAttributes.addFlashAttribute("error",
                        "Số lượng trong giỏ đã đạt mức tồn kho hiện tại.");
            } else {
                redirectAttributes.addFlashAttribute("success", "Đã thêm sản phẩm vào giỏ hàng.");
            }
        } catch (BusinessException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/products/" + id;
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam String selectedSize,
                         @RequestParam int quantity,
                         Authentication auth,
                         HttpSession session,
                         RedirectAttributes redirectAttributes) {
        try {
            CartService.CartUpdateResult result = cart.update(id, selectedSize, quantity, auth, session);
            if (!result.lineFound()) {
                redirectAttributes.addFlashAttribute("error",
                        "Sản phẩm này không còn trong giỏ hàng.");
            } else if (result.removed()) {
                String message = quantity <= 0
                        ? "Đã xóa sản phẩm khỏi giỏ hàng."
                        : "Kích cỡ này đã hết hàng nên sản phẩm đã được xóa khỏi giỏ.";
                redirectAttributes.addFlashAttribute(quantity <= 0 ? "success" : "error", message);
            } else if (result.limitedByStock()) {
                redirectAttributes.addFlashAttribute("warning",
                        "Size " + selectedSize + " chỉ còn " + result.appliedQuantity()
                                + " sản phẩm; giỏ hàng đã được điều chỉnh về mức này.");
            } else {
                redirectAttributes.addFlashAttribute("success", "Đã cập nhật giỏ hàng.");
            }
        } catch (BusinessException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/cart";
    }

    @PostMapping("/remove/{id}")
    public String remove(@PathVariable Long id,
                         @RequestParam String selectedSize,
                         Authentication auth,
                         HttpSession session,
                         RedirectAttributes redirectAttributes) {
        try {
            cart.remove(id, selectedSize, auth, session);
            redirectAttributes.addFlashAttribute("success", "Đã xóa sản phẩm khỏi giỏ hàng.");
        } catch (BusinessException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/cart";
    }
}
