package com.example.lshoestore.service;

import com.example.lshoestore.exception.BusinessException;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private Map<String, CartItem> getGuestCart(HttpSession session) {
        Object raw = session.getAttribute(GUEST_CART_KEY);
        if (raw instanceof Map<?, ?> existing) {
            boolean validMap = existing.entrySet().stream().allMatch(entry -> {
                if (!(entry.getKey() instanceof String key)
                        || !(entry.getValue() instanceof CartItem item)
                        || item.getProduct() == null || item.getProduct().getId() == null
                        || item.getQuantity() <= 0) return false;
                return key.equals(lineKey(item.getProduct().getId(), item.getSelectedSize()));
            });
            if (validMap) return (Map<String, CartItem>) existing;

            // Recover valid cart lines from older or malformed session data instead of crashing
            // or silently discarding the whole guest cart.
            Map<String, CartItem> recovered = new LinkedHashMap<>();
            for (Object value : existing.values()) {
                if (!(value instanceof CartItem item) || item.getProduct() == null
                        || item.getProduct().getId() == null || item.getQuantity() <= 0) continue;
                String key = lineKey(item.getProduct().getId(), item.getSelectedSize());
                CartItem current = recovered.get(key);
                long mergedQuantity = (long) item.getQuantity()
                        + (current == null ? 0L : current.getQuantity());
                recovered.put(key, new CartItem(
                        item.getProduct(),
                        (int) Math.min(mergedQuantity, Integer.MAX_VALUE),
                        cleanSize(item.getSelectedSize())));
            }
            session.setAttribute(GUEST_CART_KEY, recovered);
            return recovered;
        }
        Map<String, CartItem> cart = new LinkedHashMap<>();
        session.setAttribute(GUEST_CART_KEY, cart);
        return cart;
    }

    @Transactional
    public boolean add(Long productId, String requestedSize, Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) {
            User user = getUserForUpdate(auth);
            Product product = lockAvailableProduct(productId);
            String selectedSize = normalizeRequestedSize(product, requestedSize);
            List<SavedCartItem> productRows = savedCartRepo.findAllByUserAndProductIdWithLock(user, productId);
            long totalQuantity = productRows.stream().mapToLong(SavedCartItem::getQuantity).sum();
            if (totalQuantity >= product.getStock()) return false;

            SavedCartItem matching = productRows.stream()
                    .filter(item -> sameSize(item.getSelectedSize(), selectedSize))
                    .findFirst().orElse(null);
            if (matching == null) {
                savedCartRepo.saveAndFlush(new SavedCartItem(user, product, 1, selectedSize));
            } else {
                matching.setSelectedSize(selectedSize);
                matching.setQuantity(matching.getQuantity() + 1);
            }
        } else {
            Product product = lockAvailableProduct(productId);
            String selectedSize = normalizeRequestedSize(product, requestedSize);
            synchronized (session) {
                Map<String, CartItem> guest = getGuestCart(session);
                long totalQuantity = guest.values().stream()
                        .filter(item -> item != null && item.getProduct() != null
                                && productId.equals(item.getProduct().getId()))
                        .mapToLong(CartItem::getQuantity)
                        .sum();
                if (totalQuantity >= product.getStock()) return false;
                String key = lineKey(productId, selectedSize);
                CartItem current = guest.get(key);
                int currentQuantity = current == null ? 0 : current.getQuantity();
                guest.put(key, new CartItem(product, currentQuantity + 1, selectedSize));
            }
        }
        return true;
    }

    @Transactional
    public void update(Long productId, String requestedSize, int quantity,
                       Authentication auth, HttpSession session) {
        String selectedSize = cleanSize(requestedSize);
        if (quantity <= 0) {
            remove(productId, selectedSize, auth, session);
            return;
        }

        if (isLoggedIn(auth)) {
            User user = getUserForUpdate(auth);
            Product product = productRepository.findByIdWithLock(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            selectedSize = normalizeRequestedSize(product, selectedSize);
            List<SavedCartItem> productRows = savedCartRepo.findAllByUserAndProductIdWithLock(user, productId);
            String finalSelectedSize = selectedSize;
            SavedCartItem matching = productRows.stream()
                    .filter(item -> sameSize(item.getSelectedSize(), finalSelectedSize))
                    .findFirst().orElse(null);
            if (matching == null) return;

            long otherQuantity = productRows.stream()
                    .filter(item -> item != matching)
                    .mapToLong(SavedCartItem::getQuantity)
                    .sum();
            int available = (int) Math.max((long) product.getStock() - otherQuantity, 0L);
            int capped = Math.min(quantity, available);
            if (!product.isActive() || capped <= 0) savedCartRepo.delete(matching);
            else {
                matching.setSelectedSize(selectedSize);
                matching.setQuantity(capped);
            }
        } else {
            Product product = productRepository.findByIdWithLock(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            selectedSize = normalizeRequestedSize(product, selectedSize);
            synchronized (session) {
                Map<String, CartItem> guest = getGuestCart(session);
                String key = lineKey(productId, selectedSize);
                if (!guest.containsKey(key)) return;
                long otherQuantity = guest.entrySet().stream()
                        .filter(entry -> entry.getValue() != null
                                && entry.getValue().getProduct() != null
                                && productId.equals(entry.getValue().getProduct().getId())
                                && !entry.getKey().equals(key))
                        .mapToLong(entry -> entry.getValue().getQuantity())
                        .sum();
                int available = (int) Math.max((long) product.getStock() - otherQuantity, 0L);
                int capped = Math.min(quantity, available);
                if (!product.isActive() || capped <= 0) guest.remove(key);
                else guest.put(key, new CartItem(product, capped, selectedSize));
            }
        }
    }

    @Transactional
    public void remove(Long productId, String selectedSize, Authentication auth, HttpSession session) {
        String normalizedSize = cleanSize(selectedSize);
        if (isLoggedIn(auth)) {
            User user = getUserForUpdate(auth);
            List<SavedCartItem> rows = savedCartRepo.findAllByUserAndProductIdWithLock(user, productId);
            List<SavedCartItem> matchingRows = rows.stream()
                    .filter(item -> sameSize(item.getSelectedSize(), normalizedSize))
                    .toList();
            if (!matchingRows.isEmpty()) savedCartRepo.deleteAll(matchingRows);
        } else {
            synchronized (session) {
                getGuestCart(session).remove(lineKey(productId, normalizedSize));
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
        return isLoggedIn(auth)
                ? synchronizeDatabaseCart(getUserForUpdate(auth))
                : synchronizeGuestCart(session);
    }

    private List<CartItem> synchronizeDatabaseCart(User user) {
        List<CartItem> result = new ArrayList<>();
        List<SavedCartItem> rows = savedCartRepo.findByUserOrderByProduct_IdAscSelectedSizeAsc(user);
        Map<Long, Integer> remainingStock = new HashMap<>();

        for (SavedCartItem row : rows) {
            Product product = productRepository.findById(row.getProduct().getId()).orElse(null);
            if (product == null || !product.isActive() || product.getStock() <= 0) {
                savedCartRepo.delete(row);
                continue;
            }
            String selectedSize = normalizeStoredSize(product, row.getSelectedSize());
            if (selectedSize == null) {
                savedCartRepo.delete(row);
                continue;
            }
            int remaining = remainingStock.computeIfAbsent(product.getId(), ignored -> product.getStock());
            int quantity = Math.min(Math.max(row.getQuantity(), 0), remaining);
            if (quantity <= 0) {
                savedCartRepo.delete(row);
                continue;
            }
            if (row.getQuantity() != quantity) row.setQuantity(quantity);
            if (!sameSize(row.getSelectedSize(), selectedSize)) row.setSelectedSize(selectedSize);
            remainingStock.put(product.getId(), remaining - quantity);
            result.add(new CartItem(product, quantity, selectedSize));
        }
        return result;
    }

    private List<CartItem> synchronizeGuestCart(HttpSession session) {
        synchronized (session) {
            Map<String, CartItem> guest = getGuestCart(session);
            List<CartItem> result = new ArrayList<>();
            Map<Long, Integer> remainingStock = new HashMap<>();
            Map<String, CartItem> refreshedCart = new LinkedHashMap<>();

            List<CartItem> sorted = guest.values().stream()
                    .filter(item -> item != null && item.getProduct() != null
                            && item.getProduct().getId() != null)
                    .sorted(Comparator.comparing((CartItem item) -> item.getProduct().getId())
                            .thenComparing(CartItem::getSelectedSize,
                                    Comparator.nullsFirst(String::compareToIgnoreCase)))
                    .toList();
            for (CartItem oldItem : sorted) {
                Product product = productRepository.findById(oldItem.getProduct().getId()).orElse(null);
                if (product == null || !product.isActive() || product.getStock() <= 0) continue;
                String selectedSize = normalizeStoredSize(product, oldItem.getSelectedSize());
                if (selectedSize == null) continue;
                int remaining = remainingStock.computeIfAbsent(product.getId(), ignored -> product.getStock());
                int quantity = Math.min(Math.max(oldItem.getQuantity(), 0), remaining);
                if (quantity <= 0) continue;
                remainingStock.put(product.getId(), remaining - quantity);
                CartItem refreshed = new CartItem(product, quantity, selectedSize);
                refreshedCart.put(lineKey(product.getId(), selectedSize), refreshed);
                result.add(refreshed);
            }
            guest.clear();
            guest.putAll(refreshedCart);
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
    public CartMergeResult mergeGuestCart(Authentication auth, HttpSession session) {
        List<GuestCartLineSnapshot> guestItems;
        synchronized (session) {
            Map<String, CartItem> guest = getGuestCart(session);
            if (guest.isEmpty()) return new CartMergeResult(List.of());
            guestItems = guest.entrySet().stream()
                    .filter(entry -> entry.getValue() != null
                            && entry.getValue().getProduct() != null
                            && entry.getValue().getProduct().getId() != null
                            && entry.getValue().getQuantity() > 0)
                    .map(entry -> new GuestCartLineSnapshot(
                            entry.getKey(),
                            entry.getValue().getProduct().getId(),
                            entry.getValue().getProduct().getName(),
                            entry.getValue().getSelectedSize(),
                            entry.getValue().getQuantity()))
                    .sorted(Comparator.comparing(GuestCartLineSnapshot::productId)
                            .thenComparing(GuestCartLineSnapshot::selectedSize,
                                    Comparator.nullsFirst(String::compareToIgnoreCase)))
                    .toList();
        }

        List<String> warnings = new ArrayList<>();
        User user = getUserForUpdate(auth);
        int index = 0;
        while (index < guestItems.size()) {
            Long productId = guestItems.get(index).productId();
            int end = index + 1;
            while (end < guestItems.size()
                    && productId.equals(guestItems.get(end).productId())) end++;

            List<GuestCartLineSnapshot> sameProductGuestItems = guestItems.subList(index, end);
            Product product = productRepository.findByIdWithLock(productId).orElse(null);
            if (product == null || !product.isActive() || product.getStock() <= 0) {
                for (GuestCartLineSnapshot item : sameProductGuestItems) {
                    warnings.add(item.productName() + " (size " + item.selectedSize()
                            + ") không còn khả dụng và không được gộp vào giỏ hàng.");
                }
                index = end;
                continue;
            }

            List<SavedCartItem> existingRows = savedCartRepo.findAllByUserAndProductIdWithLock(user, productId);
            long currentTotal = existingRows.stream().mapToLong(SavedCartItem::getQuantity).sum();
            for (GuestCartLineSnapshot guestItem : sameProductGuestItems) {
                String selectedSize = normalizeStoredSize(product, guestItem.selectedSize());
                int requested = Math.max(guestItem.quantity(), 0);
                if (selectedSize == null) {
                    warnings.add(product.getName() + " (size " + guestItem.selectedSize()
                            + ") không còn hỗ trợ kích cỡ này và không được gộp vào giỏ hàng.");
                    continue;
                }
                int accepted = (int) Math.min((long) requested,
                        Math.max((long) product.getStock() - currentTotal, 0L));
                SavedCartItem matching = existingRows.stream()
                        .filter(item -> sameSize(item.getSelectedSize(), selectedSize))
                        .findFirst().orElse(null);

                if (accepted > 0) {
                    if (matching == null) {
                        matching = savedCartRepo.save(new SavedCartItem(user, product, accepted, selectedSize));
                        existingRows.add(matching);
                    } else {
                        matching.setSelectedSize(selectedSize);
                        matching.setQuantity(matching.getQuantity() + accepted);
                    }
                    currentTotal += accepted;
                }
                if (accepted < requested) {
                    warnings.add(product.getName() + " (size " + selectedSize + "): chỉ gộp được "
                            + accepted + "/" + requested + " sản phẩm vì tổng tồn kho còn "
                            + product.getStock() + ".");
                }
            }
            index = end;
        }

        clearMergedGuestCartAfterCommit(session, guestItems);
        return new CartMergeResult(warnings);
    }


    private void clearMergedGuestCartAfterCommit(HttpSession session,
                                                  List<GuestCartLineSnapshot> mergedItems) {
        Runnable clearMergedLines = () -> {
            try {
                synchronized (session) {
                    Map<String, CartItem> guest = getGuestCart(session);
                    for (GuestCartLineSnapshot item : mergedItems) {
                        CartItem current = guest.get(item.key());
                        if (current == null) continue;

                        // Remove only the quantity that belonged to the captured guest-cart
                        // snapshot. A concurrent request may have added more of the same line
                        // while the database merge was running; that newer quantity must remain.
                        long remaining = (long) current.getQuantity() - item.quantity();
                        if (remaining <= 0) {
                            guest.remove(item.key());
                        } else {
                            guest.put(item.key(), new CartItem(
                                    current.getProduct(),
                                    (int) Math.min(remaining, Integer.MAX_VALUE),
                                    current.getSelectedSize()));
                        }
                    }
                }
            } catch (IllegalStateException ignored) {
                // The HTTP session may already have expired after the database commit.
            }
        };

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            clearMergedLines.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                clearMergedLines.run();
            }
        });
    }

    private record GuestCartLineSnapshot(String key, Long productId, String productName,
                                         String selectedSize, int quantity) {}

    private Product lockAvailableProduct(Long productId) {
        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (!product.isActive() || product.getStock() <= 0) {
            throw new BusinessException("Sản phẩm đã hết hàng hoặc không khả dụng.", "unavailable_product");
        }
        return product;
    }

    private String normalizeRequestedSize(Product product, String requestedSize) {
        List<String> sizes = product.getAvailableSizes();
        String cleaned = cleanSize(requestedSize);
        if (cleaned.isBlank()) {
            if (sizes.size() == 1 && Product.DEFAULT_SIZE.equals(sizes.getFirst())) return Product.DEFAULT_SIZE;
            throw new BusinessException("Vui lòng chọn kích cỡ trước khi thêm vào giỏ hàng.", "size_required");
        }
        return sizes.stream()
                .filter(size -> sameSize(size, cleaned))
                .findFirst()
                .orElseThrow(() -> new BusinessException("Kích cỡ đã chọn không còn khả dụng.", "invalid_size"));
    }

    private String normalizeStoredSize(Product product, String selectedSize) {
        String cleaned = cleanSize(selectedSize);
        if (cleaned.isBlank()) return product.getAvailableSizes().getFirst();
        return product.getAvailableSizes().stream()
                .filter(size -> sameSize(size, cleaned))
                .findFirst()
                .orElse(null);
    }

    private String lineKey(Long productId, String selectedSize) {
        return productId + "::" + cleanSize(selectedSize).toLowerCase(Locale.ROOT);
    }

    private String cleanSize(String value) { return value == null ? "" : value.trim(); }

    private boolean sameSize(String left, String right) {
        return cleanSize(left).equalsIgnoreCase(cleanSize(right));
    }

    public record CartMergeResult(List<String> warnings) {}
}
