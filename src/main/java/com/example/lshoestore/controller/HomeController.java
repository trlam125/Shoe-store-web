package com.example.lshoestore.controller;

import com.example.lshoestore.repository.*;
import com.example.lshoestore.service.CartService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class HomeController {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final CartService cart;

    public HomeController(ProductRepository products, CategoryRepository categories, CartService cart) {
        this.products = products;
        this.categories = categories;
        this.cart = cart;
    }

    @GetMapping("/")
    public String home(Model model, Authentication auth) {
        model.addAttribute("products", products.findByActiveTrueOrderByIdDesc());
        model.addAttribute("categories", categories.findAll());
        model.addAttribute("cartCount", cart.count(auth));
        return "index";
    }

    @GetMapping("/products")
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) Long category,
                       Model model, Authentication auth) {
        model.addAttribute("categories", categories.findAll());
        model.addAttribute("cartCount", cart.count(auth));
        if (q != null && !q.isBlank())
            model.addAttribute("products", products.findByActiveTrueAndNameContainingIgnoreCaseOrderByIdDesc(q));
        else if (category != null)
            model.addAttribute("products", products.findByActiveTrueAndCategory_IdOrderByIdDesc(category));
        else
            model.addAttribute("products", products.findByActiveTrueOrderByIdDesc());
        model.addAttribute("q", q);
        model.addAttribute("selectedCategory", category);
        return "product/list";
    }

    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id, Model model, Authentication auth) {
        model.addAttribute("product", products.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm id=" + id)));
        model.addAttribute("cartCount", cart.count(auth));
        return "product/detail";
    }
}
