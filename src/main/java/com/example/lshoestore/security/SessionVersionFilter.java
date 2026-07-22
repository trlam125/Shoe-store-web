package com.example.lshoestore.security;

import com.example.lshoestore.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class SessionVersionFilter extends OncePerRequestFilter {
    private final UserRepository users;

    public SessionVersionFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/css/") || uri.startsWith("/images/")
                || uri.startsWith("/js/") || uri.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean valid = false;
        if (authentication.getPrincipal() instanceof StoreUserPrincipal principal) {
            valid = users.findById(principal.getUserId())
                    .filter(user -> user.getSessionVersion() == principal.getSessionVersion())
                    .filter(user -> user.getEmail().equalsIgnoreCase(principal.getUsername()))
                    .filter(user -> user.getRole().equals(principal.getRole()))
                    .filter(user -> user.isEnabled())
                    .isPresent();
        }

        if (!valid) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            if (isApiRequest(request)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"session_expired\"}");
                return;
            }
            response.sendRedirect(request.getContextPath() + "/login?expired");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/chatbot/") || uri.equals("/ai/image-search/analyze");
    }
}
