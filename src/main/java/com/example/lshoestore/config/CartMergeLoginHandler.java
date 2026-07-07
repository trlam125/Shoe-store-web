package com.example.lshoestore.config;

import com.example.lshoestore.service.CartService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import java.io.IOException;
import java.util.List;

/**
 * Sau khi đăng nhập thành công, merge giỏ hàng guest (HttpSession)
 * vào giỏ hàng DB của user. Nếu có sản phẩm bị bỏ qua (inactive/hết hàng)
 * thì flash thông báo để cart/view.html hiển thị.
 */
@Component
public class CartMergeLoginHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final CartService cartService;

    public CartMergeLoginHandler(CartService cartService) {
        super("/");
        this.cartService = cartService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            List<String> skipped = cartService.mergeGuestCart(authentication, session);
            if (!skipped.isEmpty()) {
                String msg = "Một số sản phẩm không còn khả dụng đã bị xóa khỏi giỏ hàng: "
                        + String.join(", ", skipped);
                FlashMap flashMap = new FlashMap();
                flashMap.put("warning", msg);
                FlashMapManager flashMapManager = new SessionFlashMapManager();
                flashMapManager.saveOutputFlashMap(flashMap, request, response);
            }
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
