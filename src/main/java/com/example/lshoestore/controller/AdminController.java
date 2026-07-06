package com.example.lshoestore.controller;

import com.example.lshoestore.model.*;
import com.example.lshoestore.repository.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin")

public class AdminController {
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final OrderRepository orders;

    public AdminController(ProductRepository products, CategoryRepository categories, OrderRepository orders) {
        this.products = products;
        this.categories = categories;
        this.orders = orders;
    }

    @GetMapping
    public String dashboard(Model m) {
        m.addAttribute("productCount", products.count());
        m.addAttribute("orderCount", orders.count());
        return "admin/dashboard";
    }

    @GetMapping("/products")
    public String productList(Model m) {
        m.addAttribute("products", products.findAll());
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
    public String save(@ModelAttribute Product product, @RequestParam Long categoryId) {
        product.setCategory(categories.findById(categoryId).orElseThrow());
        product.setActive(true);
        products.save(product);
        return "redirect:/admin/products";
    }

    @PostMapping("/products/delete/{id}")
    public String delete(@PathVariable Long id) {
        // Soft delete: ẩn sản phẩm thay vì xóa để tránh lỗi FK với OrderItem
        Product p = products.findById(id).orElseThrow();
        p.setActive(false);
        products.save(p);
        return "redirect:/admin/products";
    }

    @GetMapping("/orders")
    public String orderList(Model m) {
        m.addAttribute("orders", orders.findAllByOrderByCreatedAtDesc());
        m.addAttribute("statuses", OrderStatus.values());
        return "admin/orders";
    }

    @PostMapping("/orders/{id}/status")
    public String status(@PathVariable Long id, @RequestParam OrderStatus status) {
        Order o = orders.findById(id).orElseThrow();
        o.setStatus(status);
        orders.save(o);
        return "redirect:/admin/orders";
    }
}
