package com.example.lshoestore.config;

import com.example.lshoestore.service.CartService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import java.io.IOException;
import java.util.List;

/**
 * Sau khi đăng nhập thành công:
 * 1. Merge giỏ hàng guest vào DB cart của user.
 * 2. Fix #19: redirect về URL trước khi login (saved request) nếu có,
 *    ngược lại về trang chủ.
 */
@Component
public class CartMergeLoginHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final CartService cartService;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public CartMergeLoginHandler(CartService cartService) {
        super("/");
        this.cartService = cartService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
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

        // Fix #19: ưu tiên redirect về URL đã được lưu (vd: /checkout), nếu không thì về "/"
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest != null) {
            String targetUrl = savedRequest.getRedirectUrl();
            requestCache.removeRequest(request, response);
            getRedirectStrategy().sendRedirect(request, response, targetUrl);
        } else {
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }
}
