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
import java.net.URI;
import java.util.List;

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
                String message = "Một số sản phẩm không còn khả dụng đã bị xóa khỏi giỏ hàng: "
                        + String.join(", ", skipped);
                FlashMap flashMap = new FlashMap();
                flashMap.put("warning", message);
                FlashMapManager flashMapManager = new SessionFlashMapManager();
                flashMapManager.saveOutputFlashMap(flashMap, request, response);
            }
        }

        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest == null) {
            super.onAuthenticationSuccess(request, response, authentication);
            return;
        }

        requestCache.removeRequest(request, response);
        getRedirectStrategy().sendRedirect(request, response, safeRelativeTarget(savedRequest));
    }

    private String safeRelativeTarget(SavedRequest savedRequest) {
        try {
            URI uri = URI.create(savedRequest.getRedirectUrl());
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) return "/";
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException exception) {
            return "/";
        }
    }
}
