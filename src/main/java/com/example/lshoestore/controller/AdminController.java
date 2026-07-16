package com.example.lshoestore.controller;

import com.example.lshoestore.model.*;
import com.example.lshoestore.repository.*;
import com.example.lshoestore.service.OrderService;
import com.example.lshoestore.service.AiAnalyticsService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")

public class AdminController {
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final AiAnalyticsService aiAnalyticsService;

    public AdminController(ProductRepository products, CategoryRepository categories,
                           OrderRepository orders, OrderService orderService,
                           AiAnalyticsService aiAnalyticsService) {
        this.products = products;
        this.categories = categories;
        this.orders = orders;
        this.orderService = orderService;
        this.aiAnalyticsService = aiAnalyticsService;
    }

    @GetMapping
    public String dashboard(Model m) {
        // Fix #18: chỉ đếm sản phẩm đang bán (active=true)
        m.addAttribute("productCount", products.findByActiveTrueOrderByIdDesc().size());
        m.addAttribute("orderCount", orders.count());
        var segments = aiAnalyticsService.getCustomerSegments();
        var forecast = aiAnalyticsService.getSalesForecast();
        m.addAttribute("aiAvailable", !segments.isEmpty() || !forecast.isEmpty());
        m.addAttribute("segments", segments.getOrDefault("segments", java.util.List.of()));
        m.addAttribute("forecast", forecast.getOrDefault("predictions", java.util.List.of()));
        return "admin/dashboard";
    }

    @GetMapping("/products")
    public String productList(@RequestParam(defaultValue = "0") int page, Model m) {
        Pageable pageable = PageRequest.of(page, 20);
        var productPage = products.findAllByOrderByIdDesc(pageable);
        m.addAttribute("products", productPage.getContent());
        m.addAttribute("currentPage", page);
        m.addAttribute("totalPages", productPage.getTotalPages());
        return "admin/products";
    }

    @GetMapping("/products/new")
    public String newProduct(Model m) {
        m.addAttribute("product", new Product());
        m.addAttribute("categories", categories.findAll());
        return "admin/product-form";
    }

    @GetMapping("/products/edit/{id}")
    public String edit(@PathVariable Long id, Model m) {
        m.addAttribute("product", products.findById(id).orElseThrow());
        m.addAttribute("categories", categories.findAll());
        return "admin/product-form";
    }

    @PostMapping("/products/save")
    public String save(@Valid @ModelAttribute Product product,
                       BindingResult bindingResult,
                       @RequestParam Long categoryId,
                       Model m) {
        if (bindingResult.hasErrors()) {
            m.addAttribute("categories", categories.findAll());
            return "admin/product-form";
        }

        product.setCategory(categories.findById(categoryId).orElseThrow());

        if (product.getId() != null) {
            // Fix #2: load từ DB trước để đảm bảo ID hợp lệ, giữ ảnh cũ
            // active đã được bind từ form checkbox — không cần override
            Product existing = products.findById(product.getId()).orElseThrow();
            if (product.getImageUrl() == null || product.getImageUrl().isBlank()) {
                product.setImageUrl(existing.getImageUrl());
            }
        } else {
            product.setActive(true);
        }

        products.save(product);
        return "redirect:/admin/products";
    }

    // Fix #17: toggle active/inactive thay vì chỉ soft-delete một chiều
    @PostMapping("/products/toggle/{id}")
    public String toggleActive(@PathVariable Long id) {
        Product p = products.findById(id).orElseThrow();
        p.setActive(!p.isActive());
        products.save(p);
        return "redirect:/admin/products";
    }

    @GetMapping("/orders")
    public String orderList(@RequestParam(defaultValue = "0") int page, Model m) {
        Pageable pageable = PageRequest.of(page, 20);
        var orderPage = orders.findAllByOrderByCreatedAtDesc(pageable);
        m.addAttribute("orders", orderPage.getContent());
        m.addAttribute("statuses", OrderStatus.values());
        m.addAttribute("currentPage", page);
        m.addAttribute("totalPages", orderPage.getTotalPages());
        return "admin/orders";
    }

    @PostMapping("/orders/{id}/status")
    public String status(@PathVariable Long id, @RequestParam OrderStatus status,
                         RedirectAttributes redirectAttrs) {
        try {
            // Fix #3 #4: stock rollback + state machine trong OrderService
            orderService.updateStatus(id, status);
        } catch (IllegalStateException e) {
            redirectAttrs.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/orders";
    }
}
