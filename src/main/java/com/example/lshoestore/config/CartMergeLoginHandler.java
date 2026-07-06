package com.example.lshoestore.config;

import com.example.lshoestore.service.CartService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Sau khi đăng nhập thành công, merge giỏ hàng guest (session)
 * vào giỏ hàng DB của user, rồi redirect về trang chủ.
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
        cartService.mergeGuestCart(authentication);
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
