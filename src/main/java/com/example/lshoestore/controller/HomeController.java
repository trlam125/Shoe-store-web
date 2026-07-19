package com.example.lshoestore.controller;

import com.example.lshoestore.model.Product;
import com.example.lshoestore.repository.CategoryRepository;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.service.CartService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HomeController {
    private static final int CATALOG_PAGE_SIZE = 12;

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final CartService cart;

    public HomeController(ProductRepository products, CategoryRepository categories, CartService cart) {
        this.products = products;
        this.categories = categories;
        this.cart = cart;
    }

    @GetMapping("/")
    public String home(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("products", products.findTop12ByActiveTrueOrderByIdDesc());
        model.addAttribute("categories", categories.findAll());
        model.addAttribute("cartCount", cart.count(auth, session));
        return "index";
    }

    @GetMapping("/products")
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) Long category,
                       @RequestParam(defaultValue = "0") int page,
                       Model model,
                       Authentication auth,
                       HttpSession session) {
        String keyword = q == null ? null : q.trim();
        int requestedPage = Math.max(page, 0);
        Page<Product> productPage = findProductPage(keyword, category, requestedPage);

        if (productPage.getTotalPages() > 0 && requestedPage >= productPage.getTotalPages()) {
            requestedPage = productPage.getTotalPages() - 1;
            productPage = findProductPage(keyword, category, requestedPage);
        }

        int startPage = Math.max(0, productPage.getNumber() - 2);
        int endPage = Math.min(Math.max(productPage.getTotalPages() - 1, 0), productPage.getNumber() + 2);

        model.addAttribute("categories", categories.findAll());
        model.addAttribute("cartCount", cart.count(auth, session));
        model.addAttribute("products", productPage.getContent());
        model.addAttribute("productPage", productPage);
        model.addAttribute("startPage", startPage);
        model.addAttribute("endPage", endPage);
        model.addAttribute("q", keyword);
        model.addAttribute("selectedCategory", category);
        return "product/list";
    }

    private Page<Product> findProductPage(String keyword, Long category, int page) {
        Pageable pageable = PageRequest.of(page, CATALOG_PAGE_SIZE);
        if (keyword != null && !keyword.isBlank() && category != null) {
            return products.findByActiveTrueAndCategory_IdAndNameContainingIgnoreCaseOrderByIdDesc(
                    category, keyword, pageable);
        }
        if (keyword != null && !keyword.isBlank()) {
            return products.findByActiveTrueAndNameContainingIgnoreCaseOrderByIdDesc(keyword, pageable);
        }
        if (category != null) {
            return products.findByActiveTrueAndCategory_IdOrderByIdDesc(category, pageable);
        }
        return products.findByActiveTrueOrderByIdDesc(pageable);
    }

    @GetMapping("/access-denied")
    public String accessDenied(Model model,
                               Authentication auth,
                               HttpSession session,
                               HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        model.addAttribute("cartCount", cart.count(auth, session));
        return "error/403";
    }

    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id,
                         Model model,
                         Authentication auth,
                         HttpSession session,
                         HttpServletResponse response) {
        Product product = products.findById(id).orElse(null);
        if (product == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("cartCount", cart.count(auth, session));
            return "error/404";
        }
        if (!product.isActive()) {
            return "redirect:/products";
        }
        model.addAttribute("product", product);
        model.addAttribute("cartCount", cart.count(auth, session));
        return "product/detail";
    }
}
