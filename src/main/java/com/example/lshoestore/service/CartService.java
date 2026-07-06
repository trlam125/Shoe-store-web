package com.example.lshoestore.service;

import com.example.lshoestore.model.*;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.repository.SavedCartItemRepository;
import com.example.lshoestore.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.annotation.SessionScope;

import java.math.BigDecimal;
import java.util.*;

/**
 * CartService hỗ trợ hai chế độ:
 * - Guest (chưa đăng nhập): lưu trong session map.
 * - User đã đăng nhập: lưu vào bảng saved_cart_items trong DB,
 *   giỏ hàng tồn tại xuyên suốt các phiên đăng nhập.
 *
 * Khi user đăng nhập, CartMergeHandler gọi mergeGuestCart() để
 * chuyển toàn bộ session cart vào DB cart.
 */
@Service
@SessionScope
public class CartService {

    private final ProductRepository productRepository;
    private final SavedCartItemRepository savedCartRepo;
    private final UserRepository userRepository;

    // Guest cart — chỉ dùng khi chưa đăng nhập
    private final Map<Long, CartItem> guestItems = new LinkedHashMap<>();

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

    /** Chuyển DB rows → CartItem collection để controller/template dùng thống nhất */
    private Collection<CartItem> dbItemsAsCartItems(Authentication auth) {
        User u = getUser(auth);
        List<CartItem> result = new ArrayList<>();
        for (SavedCartItem sci : savedCartRepo.findByUser(u)) {
            result.add(new CartItem(sci.getProduct(), sci.getQuantity()));
        }
        return result;
    }

    // ------------------------------------------------------------------ //
    //  Public API — tất cả method đều nhận Authentication
    // ------------------------------------------------------------------ //

    @Transactional
    public boolean add(Long productId, Authentication auth) {
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
    public void update(Long productId, int quantity, Authentication auth) {
        if (isLoggedIn(auth)) {
            User u = getUser(auth);
            savedCartRepo.findByUserAndProductId(u, productId).ifPresent(sci -> {
                if (quantity <= 0) savedCartRepo.delete(sci);
                else {
                    sci.setQuantity(quantity);
                    savedCartRepo.save(sci);
                }
            });
        } else {
            if (quantity <= 0) guestItems.remove(productId);
            else if (guestItems.containsKey(productId))
                guestItems.get(productId).setQuantity(quantity);
        }
    }

    @Transactional
    public void remove(Long productId, Authentication auth) {
        if (isLoggedIn(auth)) {
            User u = getUser(auth);
            savedCartRepo.findByUserAndProductId(u, productId)
                    .ifPresent(savedCartRepo::delete);
        } else {
            guestItems.remove(productId);
        }
    }

    @Transactional
    public void clear(Authentication auth) {
        if (isLoggedIn(auth)) {
            savedCartRepo.deleteByUser(getUser(auth));
        } else {
            guestItems.clear();
        }
    }

    public Collection<CartItem> getItems(Authentication auth) {
        if (isLoggedIn(auth)) return dbItemsAsCartItems(auth);
        return guestItems.values();
    }

    public int count(Authentication auth) {
        return getItems(auth).stream().mapToInt(CartItem::getQuantity).sum();
    }

    public BigDecimal total(Authentication auth) {
        return getItems(auth).stream()
                .map(i -> i.getProduct().getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isEmpty(Authentication auth) {
        return getItems(auth).isEmpty();
    }

    /**
     * Gọi sau khi user đăng nhập thành công.
     * Merge toàn bộ guest cart (session) vào DB cart, rồi xóa session cart.
     */
    @Transactional
    public void mergeGuestCart(Authentication auth) {
        if (guestItems.isEmpty()) return;
        User u = getUser(auth);
        for (CartItem ci : guestItems.values()) {
            Product p = ci.getProduct();
            if (!p.isActive()) continue;
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
    }
}
