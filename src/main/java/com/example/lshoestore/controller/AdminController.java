package com.example.lshoestore.controller;

import com.example.lshoestore.dto.ProductForm;
import com.example.lshoestore.dto.CustomerUpdateForm;
import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.model.OrderStatus;
import com.example.lshoestore.repository.CategoryRepository;
import com.example.lshoestore.repository.OrderRepository;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.repository.UserRepository;
import com.example.lshoestore.service.AiAnalyticsService;
import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.CustomerAdminService;
import com.example.lshoestore.service.OrderService;
import com.example.lshoestore.service.ProductAdminService;
import com.example.lshoestore.service.ProductImageStorageService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.multipart.MultipartFile;

@Controller
@RequestMapping("/admin")
public class AdminController {
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final ProductAdminService productAdminService;
    private final ProductImageStorageService productImageStorage;
    private final AiAnalyticsService aiAnalyticsService;
    private final CartService cartService;
    private final UserRepository users;
    private final CustomerAdminService customerAdminService;

    public AdminController(ProductRepository products, CategoryRepository categories,
                           OrderRepository orders, OrderService orderService,
                           ProductAdminService productAdminService,
                           ProductImageStorageService productImageStorage,
                           AiAnalyticsService aiAnalyticsService,
                           CartService cartService,
                           UserRepository users,
                           CustomerAdminService customerAdminService) {
        this.products = products;
        this.categories = categories;
        this.orders = orders;
        this.orderService = orderService;
        this.productAdminService = productAdminService;
        this.productImageStorage = productImageStorage;
        this.aiAnalyticsService = aiAnalyticsService;
        this.cartService = cartService;
        this.users = users;
        this.customerAdminService = customerAdminService;
    }


    @ModelAttribute("cartCount")
    public int cartCount(Authentication authentication, HttpSession session) {
        return cartService.count(authentication, session);
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("productCount", products.countByActiveTrue());
        model.addAttribute("orderCount", orders.count());
        model.addAttribute("customerCount", users.countByRoleIgnoreCase("ROLE_USER"));
        model.addAttribute("lockedCustomerCount", users.countByRoleIgnoreCaseAndEnabledFalse("ROLE_USER"));
        var segments = aiAnalyticsService.getCustomerSegments();
        var forecast = aiAnalyticsService.getSalesForecast();
        model.addAttribute("aiAvailable", !segments.isEmpty() || !forecast.isEmpty());
        model.addAttribute("segments", segments.getOrDefault("segments", java.util.List.of()));
        model.addAttribute("forecast", forecast.getOrDefault("predictions", java.util.List.of()));
        return "admin/dashboard";
    }


    @GetMapping("/customers")
    public String customerList(@RequestParam(defaultValue = "") String q,
                               @RequestParam(defaultValue = "all") String status,
                               @RequestParam(defaultValue = "0") int page,
                               Model model) {
        int requestedPage = Math.max(page, 0);
        Boolean enabled = switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "active" -> Boolean.TRUE;
            case "locked" -> Boolean.FALSE;
            default -> null;
        };

        var customerPage = customerAdminService.search(q, enabled, PageRequest.of(requestedPage, 20, Sort.by(Sort.Direction.DESC, "id")));
        if (customerPage.getTotalPages() == 0) {
            requestedPage = 0;
        } else if (requestedPage >= customerPage.getTotalPages()) {
            requestedPage = customerPage.getTotalPages() - 1;
            customerPage = customerAdminService.search(q, enabled, PageRequest.of(requestedPage, 20, Sort.by(Sort.Direction.DESC, "id")));
        }

        model.addAttribute("customers", customerPage.getContent());
        model.addAttribute("q", q == null ? "" : q.trim());
        model.addAttribute("status", status);
        model.addAttribute("currentPage", requestedPage);
        model.addAttribute("totalPages", customerPage.getTotalPages());
        model.addAttribute("totalCustomers", customerPage.getTotalElements());
        return "admin/customers";
    }

    @GetMapping("/customers/{id}")
    public String customerDetail(@PathVariable Long id, Model model) {
        model.addAttribute("customer", customerAdminService.getDetail(id));
        return "admin/customer-detail";
    }


    @GetMapping("/customers/{id}/edit")
    public String editCustomer(@PathVariable Long id,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        try {
            var detail = customerAdminService.getDetail(id);
            model.addAttribute("customer", detail);
            model.addAttribute("customerForm", customerAdminService.getUpdateForm(id));
            return "admin/customer-form";
        } catch (BusinessException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
            return "redirect:/admin/customers/" + id;
        }
    }

    @PostMapping("/customers/{id}/edit")
    public String updateCustomer(@PathVariable Long id,
                                 @Valid @ModelAttribute("customerForm") CustomerUpdateForm form,
                                 BindingResult bindingResult,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        try {
            customerAdminService.assertProfileEditable(id);
            if (bindingResult.hasErrors()) {
                model.addAttribute("customer", customerAdminService.getDetail(id));
                return "admin/customer-form";
            }
            customerAdminService.updateProfile(id, form);
            redirectAttributes.addFlashAttribute("success", "Đã cập nhật thông tin tài khoản.");
        } catch (BusinessException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/customers/" + id;
    }

    @PostMapping("/customers/{id}/toggle")
    public String toggleCustomer(@PathVariable Long id,
                                 @RequestParam(defaultValue = "list") String returnTo,
                                 @RequestParam(defaultValue = "") String q,
                                 @RequestParam(defaultValue = "all") String status,
                                 RedirectAttributes redirectAttributes) {
        try {
            boolean enabled = customerAdminService.toggleEnabled(id);
            redirectAttributes.addFlashAttribute("success",
                    enabled ? "Đã mở khóa tài khoản khách hàng." : "Đã khóa tài khoản và đăng xuất các phiên đang hoạt động.");
        } catch (BusinessException exception) {
            redirectAttributes.addFlashAttribute("error", exception.getMessage());
        }
        if ("detail".equals(returnTo)) {
            return "redirect:/admin/customers/" + id;
        }
        redirectAttributes.addAttribute("q", q);
        redirectAttributes.addAttribute("status", status);
        return "redirect:/admin/customers";
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
                       @RequestParam(name = "imageFile", required = false) MultipartFile imageFile,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            restoreDisabledVariantStockSummary(form);
            model.addAttribute("categories", categories.findAll());
            return "admin/product-form";
        }
        String previousImageUrl = form.getId() == null
                ? null : productAdminService.getForm(form.getId()).getImageUrl();
        String uploadedImageUrl = null;
        try {
            uploadedImageUrl = productImageStorage.store(imageFile);
            if (uploadedImageUrl != null) form.setImageUrl(uploadedImageUrl);
            productAdminService.save(form);
            if (previousImageUrl != null && !previousImageUrl.equals(form.getImageUrl())) {
                productImageStorage.deleteManagedIfUnreferenced(previousImageUrl);
            }
            redirectAttributes.addFlashAttribute("success", "Đã lưu sản phẩm.");
            return "redirect:/admin/products";
        } catch (BusinessException exception) {
            productImageStorage.deleteManaged(uploadedImageUrl);
            restoreDisabledVariantStockSummary(form);
            model.addAttribute("error", exception.getMessage());
            model.addAttribute("categories", categories.findAll());
            return "admin/product-form";
        } catch (RuntimeException exception) {
            productImageStorage.deleteManaged(uploadedImageUrl);
            throw exception;
        }
    }

    private void restoreDisabledVariantStockSummary(ProductForm form) {
        if (form.getId() == null) return;
        form.setDisabledVariantStockText(
                productAdminService.getForm(form.getId()).getDisabledVariantStockText());
    }

    @PostMapping("/products/toggle/{id}")
    public String toggleActive(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        productAdminService.toggleActive(id);
        redirectAttributes.addFlashAttribute("success", "Đã cập nhật trạng thái sản phẩm.");
        return "redirect:/admin/products";
    }

    @PostMapping("/products/delete/{id}")
    public String deleteProduct(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        boolean archived = productAdminService.archive(id);
        redirectAttributes.addFlashAttribute(archived ? "success" : "warning",
                archived
                        ? "Đã ẩn sản phẩm an toàn. Bạn có thể hiện lại sản phẩm bất cứ lúc nào."
                        : "Sản phẩm này đã được ẩn trước đó.");
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
