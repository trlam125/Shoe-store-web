package com.example.lshoestore.controller;

import com.example.lshoestore.dto.ProductForm;
import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.model.OrderStatus;
import com.example.lshoestore.repository.CategoryRepository;
import com.example.lshoestore.repository.OrderRepository;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.service.AiAnalyticsService;
import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.OrderService;
import com.example.lshoestore.service.ProductAdminService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class AdminController {
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final ProductAdminService productAdminService;
    private final AiAnalyticsService aiAnalyticsService;
    private final CartService cartService;

    public AdminController(ProductRepository products, CategoryRepository categories,
                           OrderRepository orders, OrderService orderService,
                           ProductAdminService productAdminService,
                           AiAnalyticsService aiAnalyticsService,
                           CartService cartService) {
        this.products = products;
        this.categories = categories;
        this.orders = orders;
        this.orderService = orderService;
        this.productAdminService = productAdminService;
        this.aiAnalyticsService = aiAnalyticsService;
        this.cartService = cartService;
    }


    @ModelAttribute("cartCount")
    public int cartCount(Authentication authentication, HttpSession session) {
        return cartService.count(authentication, session);
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("productCount", products.countByActiveTrue());
        model.addAttribute("orderCount", orders.count());
        var segments = aiAnalyticsService.getCustomerSegments();
        var forecast = aiAnalyticsService.getSalesForecast();
        model.addAttribute("aiAvailable", !segments.isEmpty() || !forecast.isEmpty());
        model.addAttribute("segments", segments.getOrDefault("segments", java.util.List.of()));
        model.addAttribute("forecast", forecast.getOrDefault("predictions", java.util.List.of()));
        return "admin/dashboard";
    }

    @GetMapping("/products")
    public String productList(@RequestParam(defaultValue = "0") int page, Model model) {
        int requestedPage = Math.max(page, 0);
        var productPage = products.findAllByOrderByIdDesc(PageRequest.of(requestedPage, 20));
        if (productPage.getTotalPages() == 0) {
            requestedPage = 0;
        } else if (requestedPage >= productPage.getTotalPages()) {
            requestedPage = productPage.getTotalPages() - 1;
            productPage = products.findAllByOrderByIdDesc(PageRequest.of(requestedPage, 20));
        }
        model.addAttribute("products", productPage.getContent());
        model.addAttribute("currentPage", requestedPage);
        model.addAttribute("totalPages", productPage.getTotalPages());
        return "admin/products";
    }

    @GetMapping("/products/new")
    public String newProduct(Model model) {
        model.addAttribute("productForm", new ProductForm());
        model.addAttribute("categories", categories.findAll());
        return "admin/product-form";
    }

    @GetMapping("/products/edit/{id}")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("productForm", productAdminService.getForm(id));
        model.addAttribute("categories", categories.findAll());
        return "admin/product-form";
    }

    @PostMapping("/products/save")
    public String save(@Valid @ModelAttribute("productForm") ProductForm form,
                       BindingResult bindingResult,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("categories", categories.findAll());
            return "admin/product-form";
        }
        try {
            productAdminService.save(form);
            redirectAttributes.addFlashAttribute("success", "Đã lưu sản phẩm.");
            return "redirect:/admin/products";
        } catch (BusinessException exception) {
            model.addAttribute("error", exception.getMessage());
            model.addAttribute("categories", categories.findAll());
            return "admin/product-form";
        }
    }

    @PostMapping("/products/toggle/{id}")
    public String toggleActive(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        productAdminService.toggleActive(id);
        redirectAttributes.addFlashAttribute("success", "Đã cập nhật trạng thái sản phẩm.");
        return "redirect:/admin/products";
    }

    @GetMapping("/orders")
    public String orderList(@RequestParam(defaultValue = "0") int page, Model model) {
        int requestedPage = Math.max(page, 0);
        var orderPage = orders.findAllByOrderByCreatedAtDesc(PageRequest.of(requestedPage, 20));
        if (orderPage.getTotalPages() == 0) {
            requestedPage = 0;
        } else if (requestedPage >= orderPage.getTotalPages()) {
            requestedPage = orderPage.getTotalPages() - 1;
            orderPage = orders.findAllByOrderByCreatedAtDesc(PageRequest.of(requestedPage, 20));
        }
        model.addAttribute("orders", orderPage.getContent());
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("currentPage", requestedPage);
        model.addAttribute("totalPages", orderPage.getTotalPages());
        return "admin/orders";
    }

    @PostMapping("/orders/{id}/status")
    public String status(@PathVariable Long id, @RequestParam OrderStatus status,
                         RedirectAttributes redirectAttributes) {
        try {
            orderService.updateStatus(id, status);
            redirectAttributes.addFlashAttribute("success", "Đã cập nhật trạng thái đơn hàng.");
        } catch (BusinessException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/orders";
    }
}
