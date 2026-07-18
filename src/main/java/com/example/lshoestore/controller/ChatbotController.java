package com.example.lshoestore.controller;

import com.example.lshoestore.service.ChatbotService;
import com.example.lshoestore.service.ChatbotService.ChatMessage;
import com.example.lshoestore.service.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/chatbot")
public class ChatbotController {
    private static final int MAX_MESSAGE_LENGTH = 2_000;
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final int MAX_HISTORY_CHARACTERS = 8_000;

    private final ChatbotService chatbotService;
    private final RequestRateLimiter rateLimiter;
    private final int requestsPerMinute;

    public ChatbotController(ChatbotService chatbotService,
                             RequestRateLimiter rateLimiter,
                             @Value("${app.rate-limit.chat-per-minute:20}") int requestsPerMinute) {
        this.chatbotService = chatbotService;
        this.rateLimiter = rateLimiter;
        this.requestsPerMinute = Math.max(requestsPerMinute, 1);
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(@RequestBody ChatRequest request,
                                                     HttpServletRequest servletRequest,
                                                     Authentication authentication) {
        if (!rateLimiter.allow("chat", servletRequest, authentication,
                requestsPerMinute, Duration.ofMinutes(1))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("reply", "Bạn đang gửi quá nhanh. Vui lòng thử lại sau một phút."));
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("reply", "Vui lòng nhập nội dung cần tư vấn."));
        }
        if (request.message().length() > MAX_MESSAGE_LENGTH || !validHistory(request.history())) {
            return ResponseEntity.badRequest().body(Map.of("reply", "Nội dung hội thoại quá dài."));
        }
        try {
            String reply = chatbotService.chat(request.message().trim(), request.history());
            return ResponseEntity.ok(Map.of("reply", reply));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("reply", exception.getMessage()));
        }
    }

    private boolean validHistory(List<ChatMessage> history) {
        if (history == null) return true;
        if (history.size() > MAX_HISTORY_MESSAGES) return false;
        int total = 0;
        for (ChatMessage item : history) {
            if (item == null || item.content() == null) continue;
            if (!"user".equals(item.role()) && !"assistant".equals(item.role())) return false;
            if (item.content().length() > MAX_MESSAGE_LENGTH) return false;
            total += item.content().length();
            if (total > MAX_HISTORY_CHARACTERS) return false;
        }
        return true;
    }

    public record ChatRequest(String message, List<ChatMessage> history) {}
}
