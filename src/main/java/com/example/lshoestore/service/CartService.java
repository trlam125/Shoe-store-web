package com.example.lshoestore.service;

import com.example.lshoestore.exception.ResourceNotFoundException;
import com.example.lshoestore.model.CartItem;
import com.example.lshoestore.model.Product;
import com.example.lshoestore.model.SavedCartItem;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.repository.SavedCartItemRepository;
import com.example.lshoestore.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private boolean isLoggedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }

    public User getUser(Authentication auth) {
        if (!isLoggedIn(auth)) throw new ResourceNotFoundException("User not found");
        return userRepository.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private User getUserForUpdate(Authentication auth) {
        User user = getUser(auth);
        return userRepository.findByIdWithLock(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
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

    @Transactional
    public boolean add(Long productId, Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) {
            User user = getUserForUpdate(auth);
            Product product = productRepository.findByIdWithLock(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            if (!product.isActive() || product.getStock() <= 0) return false;
            Optional<SavedCartItem> existing = savedCartRepo
                    .findByUserAndProductIdWithLock(user, productId);
            if (existing.isPresent()) {
                SavedCartItem item = existing.get();
                if (item.getQuantity() >= product.getStock()) return false;
                item.setQuantity(item.getQuantity() + 1);
            } else {
                savedCartRepo.saveAndFlush(new SavedCartItem(user, product, 1));
            }
        } else {
            Product product = productRepository.findByIdWithLock(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            if (!product.isActive() || product.getStock() <= 0) return false;
            synchronized (session) {
                Map<Long, CartItem> guest = getGuestCart(session);
                CartItem current = guest.get(productId);
                int currentQuantity = current == null ? 0 : current.getQuantity();
                if (currentQuantity >= product.getStock()) return false;
                guest.put(productId, new CartItem(product, currentQuantity + 1));
            }
        }
        return true;
    }

    @Transactional
    public void update(Long productId, int quantity, Authentication auth, HttpSession session) {
        if (quantity <= 0) {
            remove(productId, auth, session);
            return;
        }

        if (isLoggedIn(auth)) {
            User user = getUserForUpdate(auth);
            Product product = productRepository.findByIdWithLock(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            int capped = Math.min(quantity, Math.max(product.getStock(), 0));
            savedCartRepo.findByUserAndProductIdWithLock(user, productId).ifPresent(item -> {
                if (!product.isActive() || capped <= 0) savedCartRepo.delete(item);
                else item.setQuantity(capped);
            });
        } else {
            Product product = productRepository.findByIdWithLock(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            int capped = Math.min(quantity, Math.max(product.getStock(), 0));
            synchronized (session) {
                Map<Long, CartItem> guest = getGuestCart(session);
                if (!product.isActive() || capped <= 0) guest.remove(productId);
                else if (guest.containsKey(productId)) guest.put(productId, new CartItem(product, capped));
            }
        }
    }

    @Transactional
    public void remove(Long productId, Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) {
            User user = getUserForUpdate(auth);
            savedCartRepo.findByUserAndProductIdWithLock(user, productId).ifPresent(savedCartRepo::delete);
        } else {
            synchronized (session) {
                getGuestCart(session).remove(productId);
            }
        }
    }

    @Transactional
    public void clear(Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) {
            savedCartRepo.deleteByUser(getUserForUpdate(auth));
        } else {
            synchronized (session) {
                getGuestCart(session).clear();
            }
        }
    }

    @Transactional
    public List<CartItem> getItems(Authentication auth, HttpSession session) {
        return isLoggedIn(auth) ? synchronizeDatabaseCart(getUser(auth)) : synchronizeGuestCart(session);
    }

    private List<CartItem> synchronizeDatabaseCart(User user) {
        List<CartItem> result = new ArrayList<>();
        List<SavedCartItem> rows = savedCartRepo.findByUserOrderByIdAsc(user);
        for (SavedCartItem row : rows) {
            Product product = productRepository.findById(row.getProduct().getId()).orElse(null);
            if (product == null || !product.isActive() || product.getStock() <= 0) {
                savedCartRepo.delete(row);
                continue;
            }
            int quantity = Math.min(row.getQuantity(), product.getStock());
            if (quantity <= 0) {
                savedCartRepo.delete(row);
                continue;
            }
            if (row.getQuantity() != quantity) row.setQuantity(quantity);
            result.add(new CartItem(product, quantity));
        }
        return result;
    }

    private List<CartItem> synchronizeGuestCart(HttpSession session) {
        synchronized (session) {
            Map<Long, CartItem> guest = getGuestCart(session);
            List<CartItem> result = new ArrayList<>();
            Iterator<Map.Entry<Long, CartItem>> iterator = guest.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, CartItem> entry = iterator.next();
                Product product = productRepository.findById(entry.getKey()).orElse(null);
                if (product == null || !product.isActive() || product.getStock() <= 0) {
                    iterator.remove();
                    continue;
                }
                int quantity = Math.min(entry.getValue().getQuantity(), product.getStock());
                if (quantity <= 0) {
                    iterator.remove();
                    continue;
                }
                CartItem refreshed = new CartItem(product, quantity);
                entry.setValue(refreshed);
                result.add(refreshed);
            }
            return result;
        }
    }

    @Transactional
    public int count(Authentication auth, HttpSession session) {
        return getItems(auth, session).stream().mapToInt(CartItem::getQuantity).sum();
    }

    @Transactional
    public BigDecimal total(Authentication auth, HttpSession session) {
        return getItems(auth, session).stream()
                .map(item -> item.getProduct().getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional
    public boolean isEmpty(Authentication auth, HttpSession session) {
        return getItems(auth, session).isEmpty();
    }

    @Transactional
    public List<String> mergeGuestCart(Authentication auth, HttpSession session) {
        List<CartItem> guestItems;
        synchronized (session) {
            Map<Long, CartItem> guest = getGuestCart(session);
            if (guest.isEmpty()) return new ArrayList<>();
            guestItems = new ArrayList<>(guest.values());
        }
        List<String> skipped = new ArrayList<>();

        User user = getUserForUpdate(auth);
        for (CartItem guestItem : guestItems) {
            Product product = productRepository.findByIdWithLock(guestItem.getProduct().getId()).orElse(null);
            if (product == null || !product.isActive() || product.getStock() <= 0) {
                skipped.add(guestItem.getProduct().getName());
                continue;
            }
            Optional<SavedCartItem> existing = savedCartRepo
                    .findByUserAndProductIdWithLock(user, product.getId());
            int mergedQuantity = Math.min(
                    (existing.map(SavedCartItem::getQuantity).orElse(0)) + guestItem.getQuantity(),
                    product.getStock());
            if (existing.isPresent()) existing.get().setQuantity(mergedQuantity);
            else if (mergedQuantity > 0) savedCartRepo.save(new SavedCartItem(user, product, mergedQuantity));
        }
        synchronized (session) {
            Map<Long, CartItem> currentGuest = getGuestCart(session);
            for (CartItem merged : guestItems) {
                Long productId = merged.getProduct().getId();
                CartItem current = currentGuest.get(productId);
                if (current == null) continue;
                int remaining = current.getQuantity() - merged.getQuantity();
                if (remaining <= 0) currentGuest.remove(productId);
                else currentGuest.put(productId, new CartItem(current.getProduct(), remaining));
            }
        }
        return skipped;
    }

    @Transactional(readOnly = true)
    public Collection<SavedCartItem> getLockedItemsForCheckout(User user) {
        return savedCartRepo.findByUserWithLock(user);
    }
}
