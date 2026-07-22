package com.example.lshoestore.service;

import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.exception.ResourceNotFoundException;
import com.example.lshoestore.model.CartItem;
import com.example.lshoestore.model.Product;
import com.example.lshoestore.model.ProductVariant;
import com.example.lshoestore.model.SavedCartItem;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.repository.ProductVariantRepository;
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
    private final ProductVariantRepository variantRepository;
    private final SavedCartItemRepository savedCartRepo;
    private final UserRepository userRepository;

    public CartService(ProductRepository productRepository,
                       ProductVariantRepository variantRepository,
                       SavedCartItemRepository savedCartRepo,
                       UserRepository userRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
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

    private User getActiveUserForUpdate(Authentication auth) {
        User user = getUserForUpdate(auth);
        if (!user.isEnabled()) {
            throw new BusinessException(
                    "Tài khoản đã bị khóa. Không thể thay đổi giỏ hàng.",
                    "account_disabled");
        }
        return user;
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
                        cleanSize(item.getSelectedSize()),
                        item.getAvailableStock()));
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
            User user = getActiveUserForUpdate(auth);
            InventorySelection selection = lockAvailableSelection(productId, requestedSize);
            List<SavedCartItem> productRows = savedCartRepo.findAllByUserAndProductIdWithLock(user, productId);
            SavedCartItem matching = productRows.stream()
                    .filter(item -> sameSize(item.getSelectedSize(), selection.size()))
                    .findFirst().orElse(null);
            int currentQuantity = matching == null ? 0 : matching.getQuantity();
            if (currentQuantity >= selection.variant().getStock()) return false;

            if (matching == null) {
                savedCartRepo.saveAndFlush(new SavedCartItem(user, selection.product(), 1, selection.size()));
            } else {
                matching.setSelectedSize(selection.size());
                matching.setQuantity(currentQuantity + 1);
            }
        } else {
            InventorySelection selection = lockAvailableSelection(productId, requestedSize);
            synchronized (session) {
                Map<String, CartItem> guest = getGuestCart(session);
                String key = lineKey(productId, selection.size());
                CartItem current = guest.get(key);
                int currentQuantity = current == null ? 0 : current.getQuantity();
                if (currentQuantity >= selection.variant().getStock()) return false;
                guest.put(key, new CartItem(selection.product(), currentQuantity + 1,
                        selection.size(), selection.variant().getStock()));
            }
        }
        return true;
    }

    @Transactional
    public CartUpdateResult update(Long productId, String requestedSize, int quantity,
                                   Authentication auth, HttpSession session) {
        String selectedSize = cleanSize(requestedSize);
        if (quantity <= 0) {
            boolean removed = removeInternal(productId, selectedSize, auth, session);
            return new CartUpdateResult(quantity, 0, removed, removed, false);
        }

        if (isLoggedIn(auth)) {
            // Keep the same lock order as add/checkout: user -> product -> variant -> cart rows.
            User user = getActiveUserForUpdate(auth);
            Product product = productRepository.findByIdWithLock(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            selectedSize = normalizeRequestedSize(product, selectedSize);
            ProductVariant variant = variantRepository.findByProductIdAndSizeWithLock(productId, selectedSize)
                    .orElse(null);
            boolean purchasable = product.isActive() && variant != null && variant.isEnabled()
                    && variant.getStock() > 0;
            int available = purchasable ? variant.getStock() : 0;
            int capped = Math.min(quantity, available);

            List<SavedCartItem> productRows = savedCartRepo.findAllByUserAndProductIdWithLock(user, productId);
            String finalSelectedSize = selectedSize;
            SavedCartItem matching = productRows.stream()
                    .filter(item -> sameSize(item.getSelectedSize(), finalSelectedSize))
                    .findFirst().orElse(null);
            if (matching == null) return CartUpdateResult.notFound(quantity);
            if (capped <= 0) {
                savedCartRepo.delete(matching);
                return new CartUpdateResult(quantity, 0, true, true, quantity > 0);
            }
            matching.setSelectedSize(variant.getSize());
            matching.setQuantity(capped);
            return new CartUpdateResult(quantity, capped, true, false, capped < quantity);
        }

        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        selectedSize = normalizeRequestedSize(product, selectedSize);
        ProductVariant variant = variantRepository.findByProductIdAndSizeWithLock(productId, selectedSize)
                .orElse(null);
        boolean purchasable = product.isActive() && variant != null && variant.isEnabled()
                && variant.getStock() > 0;
        int available = purchasable ? variant.getStock() : 0;
        int capped = Math.min(quantity, available);
        synchronized (session) {
            Map<String, CartItem> guest = getGuestCart(session);
            String key = lineKey(productId, selectedSize);
            if (!guest.containsKey(key)) return CartUpdateResult.notFound(quantity);
            if (capped <= 0) {
                guest.remove(key);
                return new CartUpdateResult(quantity, 0, true, true, quantity > 0);
            }
            guest.put(key, new CartItem(product, capped, variant.getSize(), available));
        }
        return new CartUpdateResult(quantity, capped, true, false, capped < quantity);
    }

    @Transactional
    public void remove(Long productId, String selectedSize, Authentication auth, HttpSession session) {
        removeInternal(productId, selectedSize, auth, session);
    }

    private boolean removeInternal(Long productId, String selectedSize,
                                   Authentication auth, HttpSession session) {
        String normalizedSize = cleanSize(selectedSize);
        if (isLoggedIn(auth)) {
            User user = getActiveUserForUpdate(auth);
            List<SavedCartItem> rows = savedCartRepo.findAllByUserAndProductIdWithLock(user, productId);
            List<SavedCartItem> matchingRows = rows.stream()
                    .filter(item -> sameSize(item.getSelectedSize(), normalizedSize))
                    .toList();
            if (matchingRows.isEmpty()) return false;
            savedCartRepo.deleteAll(matchingRows);
            return true;
        }
        synchronized (session) {
            return getGuestCart(session).remove(lineKey(productId, normalizedSize)) != null;
        }
    }

    @Transactional
    public void clear(Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) {
            savedCartRepo.deleteByUser(getActiveUserForUpdate(auth));
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
        Map<String, Integer> remainingStock = new HashMap<>();

        for (SavedCartItem row : rows) {
            Product product = productRepository.findById(row.getProduct().getId()).orElse(null);
            if (product == null || !product.isActive()) {
                savedCartRepo.delete(row);
                continue;
            }
            String selectedSize = normalizeStoredSize(product, row.getSelectedSize());
            if (selectedSize == null) {
                savedCartRepo.delete(row);
                continue;
            }
            ProductVariant variant = variantRepository.findByProductIdAndSize(product.getId(), selectedSize)
                    .orElse(null);
            if (variant == null || !variant.isEnabled() || variant.getStock() <= 0) {
                savedCartRepo.delete(row);
                continue;
            }
            String inventoryKey = lineKey(product.getId(), variant.getSize());
            int remaining = remainingStock.computeIfAbsent(inventoryKey, ignored -> variant.getStock());
            int quantity = Math.min(Math.max(row.getQuantity(), 0), remaining);
            if (quantity <= 0) {
                savedCartRepo.delete(row);
                continue;
            }
            if (row.getQuantity() != quantity) row.setQuantity(quantity);
            if (!sameSize(row.getSelectedSize(), variant.getSize())) row.setSelectedSize(variant.getSize());
            remainingStock.put(inventoryKey, remaining - quantity);
            result.add(new CartItem(product, quantity, variant.getSize(), variant.getStock()));
        }
        return result;
    }

    private List<CartItem> synchronizeGuestCart(HttpSession session) {
        synchronized (session) {
            Map<String, CartItem> guest = getGuestCart(session);
            List<CartItem> result = new ArrayList<>();
            Map<String, Integer> remainingStock = new HashMap<>();
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
                if (product == null || !product.isActive()) continue;
                String selectedSize = normalizeStoredSize(product, oldItem.getSelectedSize());
                if (selectedSize == null) continue;
                ProductVariant variant = variantRepository.findByProductIdAndSize(product.getId(), selectedSize)
                        .orElse(null);
                if (variant == null || !variant.isEnabled() || variant.getStock() <= 0) continue;
                String inventoryKey = lineKey(product.getId(), variant.getSize());
                int remaining = remainingStock.computeIfAbsent(inventoryKey, ignored -> variant.getStock());
                int quantity = Math.min(Math.max(oldItem.getQuantity(), 0), remaining);
                if (quantity <= 0) continue;
                remainingStock.put(inventoryKey, remaining - quantity);
                CartItem refreshed = new CartItem(product, quantity, variant.getSize(), variant.getStock());
                refreshedCart.put(inventoryKey, refreshed);
                result.add(refreshed);
            }
            guest.clear();
            guest.putAll(refreshedCart);
            return result;
        }
    }

    @Transactional
    public int count(Authentication auth, HttpSession session) {
        if (isLoggedIn(auth)) {
            Long total = savedCartRepo.countAvailableQuantityByUserId(getUser(auth).getId());
            return saturatingInt(total == null ? 0L : total);
        }
        long total = synchronizeGuestCart(session).stream().mapToLong(CartItem::getQuantity).sum();
        return saturatingInt(total);
    }

    private int saturatingInt(long value) {
        if (value <= 0) return 0;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
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
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Guest cart merge requires an active transaction");
        }

        Map<String, CartItem> detachedGuestCart;
        List<GuestCartLineSnapshot> guestItems;
        synchronized (session) {
            Map<String, CartItem> guest = getGuestCart(session);
            if (guest.isEmpty()) return new CartMergeResult(List.of());

            // Atomically detach the cart being merged. Requests arriving after this
            // point write to a new map and therefore cannot be removed by this merge.
            detachedGuestCart = new LinkedHashMap<>(guest);
            session.setAttribute(GUEST_CART_KEY, new LinkedHashMap<String, CartItem>());
            guestItems = detachedGuestCart.entrySet().stream()
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

        restoreDetachedGuestCartOnRollback(session, detachedGuestCart);
        List<String> warnings = new ArrayList<>();
        User user = getActiveUserForUpdate(auth);
        int index = 0;
        while (index < guestItems.size()) {
            Long productId = guestItems.get(index).productId();
            int end = index + 1;
            while (end < guestItems.size()
                    && productId.equals(guestItems.get(end).productId())) end++;

            List<GuestCartLineSnapshot> sameProductGuestItems = guestItems.subList(index, end);
            Product product = productRepository.findByIdWithLock(productId).orElse(null);
            if (product == null || !product.isActive()) {
                for (GuestCartLineSnapshot item : sameProductGuestItems) {
                    warnings.add(item.productName() + " (size " + item.selectedSize()
                            + ") không còn khả dụng và không được gộp vào giỏ hàng.");
                }
                index = end;
                continue;
            }

            Map<String, ProductVariant> variantsBySize = new HashMap<>();
            for (ProductVariant variant : variantRepository.findAllByProductIdWithLock(productId)) {
                variantsBySize.put(normalizeSizeKey(variant.getSize()), variant);
            }
            List<SavedCartItem> existingRows = savedCartRepo.findAllByUserAndProductIdWithLock(user, productId);
            for (GuestCartLineSnapshot guestItem : sameProductGuestItems) {
                String selectedSize = normalizeStoredSize(product, guestItem.selectedSize());
                int requested = Math.max(guestItem.quantity(), 0);
                if (selectedSize == null) {
                    warnings.add(product.getName() + " (size " + guestItem.selectedSize()
                            + ") không còn hỗ trợ kích cỡ này và không được gộp vào giỏ hàng.");
                    continue;
                }
                ProductVariant variant = variantsBySize.get(normalizeSizeKey(selectedSize));
                if (variant == null || !variant.isEnabled() || variant.getStock() <= 0) {
                    warnings.add(product.getName() + " (size " + selectedSize
                            + ") đã hết hàng hoặc không còn bán.");
                    continue;
                }

                SavedCartItem matching = existingRows.stream()
                        .filter(item -> sameSize(item.getSelectedSize(), variant.getSize()))
                        .findFirst().orElse(null);
                int currentQuantity = matching == null ? 0 : Math.max(matching.getQuantity(), 0);
                int availableToAdd = Math.max(variant.getStock() - currentQuantity, 0);
                int accepted = Math.min(requested, availableToAdd);

                if (accepted > 0) {
                    if (matching == null) {
                        matching = savedCartRepo.save(new SavedCartItem(
                                user, product, accepted, variant.getSize()));
                        existingRows.add(matching);
                    } else {
                        matching.setSelectedSize(variant.getSize());
                        matching.setQuantity(currentQuantity + accepted);
                    }
                    // The whole guest line is considered resolved after this merge.
                    // Any rejected quantity is surfaced through warnings instead of being
                    // left hidden in the session after the user becomes authenticated.
                }
                if (accepted < requested) {
                    warnings.add(product.getName() + " (size " + variant.getSize() + "): chỉ gộp được "
                            + accepted + "/" + requested + " sản phẩm vì chỉ còn "
                            + availableToAdd + " sản phẩm có thể thêm vào giỏ (tồn kho: "
                            + variant.getStock() + ", đã có trong giỏ: " + currentQuantity + ").");
                }
            }
            index = end;
        }

        return new CartMergeResult(warnings);
    }

    private void restoreDetachedGuestCartOnRollback(HttpSession session,
                                                     Map<String, CartItem> detachedGuestCart) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return;
                try {
                    synchronized (session) {
                        Map<String, CartItem> current = getGuestCart(session);
                        detachedGuestCart.forEach((key, detachedItem) -> {
                            CartItem existing = current.get(key);
                            long restoredQuantity = detachedItem.getQuantity()
                                    + (existing == null ? 0L : existing.getQuantity());
                            current.put(key, new CartItem(
                                    detachedItem.getProduct(),
                                    (int) Math.min(restoredQuantity, Integer.MAX_VALUE),
                                    detachedItem.getSelectedSize(),
                                    Math.max(detachedItem.getAvailableStock(),
                                            existing == null ? 0 : existing.getAvailableStock())));
                        });
                    }
                } catch (IllegalStateException ignored) {
                    // The HTTP session may already have expired while the transaction rolled back.
                }
            }
        });
    }

    private record GuestCartLineSnapshot(String key, Long productId, String productName,
                                         String selectedSize, int quantity) {}

    private InventorySelection lockAvailableSelection(Long productId, String requestedSize) {
        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (!product.isActive()) {
            throw new BusinessException("Sản phẩm không còn khả dụng.", "unavailable_product");
        }
        String selectedSize = normalizeRequestedSize(product, requestedSize);
        ProductVariant variant = variantRepository.findByProductIdAndSizeWithLock(productId, selectedSize)
                .orElseThrow(() -> new BusinessException(
                        "Kích cỡ đã chọn không còn khả dụng.", "invalid_size"));
        if (!variant.isEnabled() || variant.getStock() <= 0) {
            throw new BusinessException("Kích cỡ " + variant.getSize() + " đã hết hàng.",
                    "variant_out_of_stock");
        }
        return new InventorySelection(product, variant, variant.getSize());
    }

    private String normalizeRequestedSize(Product product, String requestedSize) {
        List<String> sizes = product.getAvailableSizes();
        String cleaned = cleanSize(requestedSize);
        if (cleaned.isBlank()) {
            if (sizes.size() == 1 && Product.DEFAULT_SIZE.equals(sizes.getFirst())) {
                return Product.DEFAULT_SIZE;
            }
            throw new BusinessException("Vui lòng chọn kích cỡ trước khi thêm vào giỏ hàng.",
                    "size_required");
        }
        return sizes.stream()
                .filter(size -> sameSize(size, cleaned))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "Kích cỡ đã chọn không còn khả dụng.", "invalid_size"));
    }

    private String normalizeStoredSize(Product product, String selectedSize) {
        String cleaned = cleanSize(selectedSize);
        if (cleaned.isBlank()) {
            List<String> sizes = product.getAvailableSizes();
            return sizes.isEmpty() ? null : sizes.getFirst();
        }
        return product.getAvailableSizes().stream()
                .filter(size -> sameSize(size, cleaned))
                .findFirst()
                .orElse(null);
    }

    private String lineKey(Long productId, String selectedSize) {
        return productId + "::" + normalizeSizeKey(selectedSize);
    }

    private String normalizeSizeKey(String value) {
        return cleanSize(value).toLowerCase(Locale.ROOT);
    }

    private String cleanSize(String value) { return value == null ? "" : value.trim(); }

    private boolean sameSize(String left, String right) {
        return cleanSize(left).equalsIgnoreCase(cleanSize(right));
    }

    private record InventorySelection(Product product, ProductVariant variant, String size) {}

    public record CartMergeResult(List<String> warnings) {}

    public record CartUpdateResult(int requestedQuantity,
                                   int appliedQuantity,
                                   boolean lineFound,
                                   boolean removed,
                                   boolean limitedByStock) {
        static CartUpdateResult notFound(int requestedQuantity) {
            return new CartUpdateResult(requestedQuantity, 0, false, false, false);
        }
    }
}
