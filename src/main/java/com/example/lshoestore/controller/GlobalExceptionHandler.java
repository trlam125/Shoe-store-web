package com.example.lshoestore.controller;

import com.example.lshoestore.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    public String notFound(ResourceNotFoundException exception, Model model,
                           HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        model.addAttribute("errorMessage", exception.getMessage());
        return "error/404";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object uploadTooLarge(HttpServletRequest request) {
        if (isAiImageSearchRequest(request)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("detail", "Ảnh tải lên vượt quá 10 MB."));
        }

        RequestContextUtils.getOutputFlashMap(request).put("error",
                "Ảnh sản phẩm không được vượt quá 5 MB. Vui lòng chọn tệp nhỏ hơn.");
        return "redirect:" + resolveProductFormPath(request);
    }

    private boolean isAiImageSearchRequest(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
        }
        return "/ai/image-search/analyze".equals(requestUri);
    }

    private String resolveProductFormPath(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) return "/admin/products";

        try {
            String path = new URI(referer).getPath();
            String contextPath = request.getContextPath();
            if (path == null) return "/admin/products";
            if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
            if ("/admin/products/new".equals(path)
                    || path.matches("/admin/products/edit/\\d+")) {
                return path;
            }
        } catch (URISyntaxException ignored) {
            // Fall back to the product list for a malformed or untrusted Referer header.
        }
        return "/admin/products";
    }
}
