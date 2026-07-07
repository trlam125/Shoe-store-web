package com.example.lshoestore.service;

import com.example.lshoestore.model.*;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.repository.SavedCartItemRepository;
import com.example.lshoestore.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * CartService hỗ trợ hai chế độ:
 * - Guest (chưa đăng nhập): lưu trong session map (qua HttpSession).
 * - User đã đăng nhập: lưu vào bảng saved_cart_items trong DB.
 *
 * Không còn @SessionScope — dùng singleton + HttpSession inject trực tiếp.
 */
@Service
public class CartService {

    private static final String GUEST_CART_KEY = "guestCart";

    private final ProductRepository productRepository;
    private final SavedCartItemRepository savedCartRepo;
    private final UserRepository userRepository;

    public CartService(ProductRepository productRepository,
                       SavedCartItemRepository savedCartRepo,
                       UserRepository userRepository) {
        this.productRepository = productRepository;
        this.savedCartRepo = savedCartRepo;
        this.userRepository = userRepository;
    }

    // ------------------------------------------------------------------ //
    //  Internal helpers
    // ------------------------------------------------------------------ //

    private boolean isLoggedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
    }

    private User getUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<Long, CartItem> getGuestCart(HttpSession session) {
        Map<Long, CartItem> cart = (Map<Long, CartItem>) session.getAttribute(GUEST_CART_KEY);
        if (cart == null) {
            cart = new LinkedHashMap<>();
            session.setAttribute(GUEST_CART_KEY, cart);
        }
        return cart;
    }

    private Collection<CartItem> dbItemsAsCartItems(Authentication auth) {
        User u = getUser(auth);
        List<CartItem> result = new ArrayList<>();
        for (SavedCartItem sci : savedCartRepo.findByUser(u)) {
            // Fix #4: reload product mới nhất từ DB để hiển thị giá/tồn kho hiện tại
            Product fresh = productRepository.findById(sci.getProduct().getId())
                    .orElse(sci.getProduct());
            result.add(new CartItem(fresh, sci.getQuantity()));
        }
        return result;
    }

    // ------------------------------------------------------------------ //
    //  Public API — tất cả method nhận Authentication + HttpSession
    // ------------------------------------------------------------------ //

    @Transactional
    public boolean add(Long productId, Authentication auth, HttpSession session) {
        Product p = productRepository.findById(productId).orElseThrow();
        if (!p.isActive() || p.getStock() <= 0) return false;

        if (isLoggedIn(auth)) {
            User u = getUser(auth);
            Optional<SavedCartItem> existing = savedCartRepo.findByUserAndProductId(u, productId);
            if (existing.isPresent()) {
                SavedCartItem sci = existing.get();
                if (sci.getQuantity() >= p.getStock()) return false;
                sci.setQuantity(sci.getQuantity() + 1);
                savedCartRepo.save(sci);
            } else {
                savedCartRepo.save(new SavedCartItem(u, p, 1));
            }
        } else {
            Map<Long, CartItem> guestItems = getGuestCart(session);
            CartItem item = guestItems.get(productId);
            if (item == null) {
                guestItems.put(productId, new CartItem(p, 1));
            } else {
                if (item.getQuantity() >= p.getStock()) return false;
                item.setQuantity(item.getQuantity() + 1);
            }
        }
        return true;
    }

    @Transactional
    public void update(Long productId, int quantity, Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) {
            User u = getUser(auth);
            savedCartRepo.findByUserAndProductId(u, productId).ifPresent(sci -> {
                if (quantity <= 0) {
                    savedCartRepo.delete(sci);
                } else {
                    Product p = productRepository.findById(productId).orElseThrow();
                    int capped = Math.min(quantity, p.getStock());
                    sci.setQuantity(capped);
                    savedCartRepo.save(sci);
                }
            });
        } else {
            Map<Long, CartItem> guestItems = getGuestCart(session);
            if (quantity <= 0) {
                guestItems.remove(productId);
            } else if (guestItems.containsKey(productId)) {
                Product p = productRepository.findById(productId).orElseThrow();
                int capped = Math.min(quantity, p.getStock());
                guestItems.get(productId).setQuantity(capped);
            }
        }
    }

    @Transactional
    public void remove(Long productId, Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) {
            User u = getUser(auth);
            savedCartRepo.findByUserAndProductId(u, productId).ifPresent(savedCartRepo::delete);
        } else {
            getGuestCart(session).remove(productId);
        }
    }

    @Transactional
    public void clear(Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) {
            savedCartRepo.deleteByUser(getUser(auth));
        } else {
            getGuestCart(session).clear();
        }
    }

    public Collection<CartItem> getItems(Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) return dbItemsAsCartItems(auth);
        return getGuestCart(session).values();
    }

    public int count(Authentication auth, HttpSession session) {
        return getItems(auth, session).stream().mapToInt(CartItem::getQuantity).sum();
    }

    public BigDecimal total(Authentication auth, HttpSession session) {
        return getItems(auth, session).stream()
                .map(i -> i.getProduct().getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isEmpty(Authentication auth, HttpSession session) {
        return getItems(auth, session).isEmpty();
    }

    /**
     * Merge guest cart → DB cart sau khi login. Gọi từ CartMergeLoginHandler.
     * Fix #6: trả về danh sách tên sản phẩm bị bỏ qua (inactive/hết hàng) để controller thông báo.
     */
    @Transactional
    public List<String> mergeGuestCart(Authentication auth, HttpSession session) {
        Map<Long, CartItem> guestItems = getGuestCart(session);
        List<String> skipped = new ArrayList<>();
        if (guestItems.isEmpty()) return skipped;

        User u = getUser(auth);
        for (CartItem ci : guestItems.values()) {
            // Reload product mới nhất từ DB
            Product p = productRepository.findById(ci.getProduct().getId()).orElse(null);
            if (p == null || !p.isActive()) {
                skipped.add(ci.getProduct().getName());
                continue;
            }
            if (p.getStock() <= 0) {
                skipped.add(p.getName() + " (hết hàng)");
                continue;
            }

            Optional<SavedCartItem> existing = savedCartRepo.findByUserAndProductId(u, p.getId());
            if (existing.isPresent()) {
                SavedCartItem sci = existing.get();
                int merged = Math.min(sci.getQuantity() + ci.getQuantity(), p.getStock());
                sci.setQuantity(merged);
                savedCartRepo.save(sci);
            } else {
                int qty = Math.min(ci.getQuantity(), p.getStock());
                if (qty > 0) savedCartRepo.save(new SavedCartItem(u, p, qty));
            }
        }
        guestItems.clear();
        return skipped;
    }
}
