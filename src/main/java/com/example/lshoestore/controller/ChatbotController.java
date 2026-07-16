package com.example.lshoestore.controller;

import com.example.lshoestore.service.ChatbotService;
import com.example.lshoestore.service.ChatbotService.ChatMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/chatbot")
public class ChatbotController {

    private static final int MAX_MESSAGE_LENGTH = 2_000;
    private final ChatbotService chatbotService;

    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("reply", "Vui lòng nhập nội dung cần tư vấn."));
        }
        if (request.message().length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("reply", "Tin nhắn quá dài. Vui lòng nhập tối đa 2.000 ký tự."));
        }

        try {
            String reply = chatbotService.chat(request.message().trim(), request.history());
            return ResponseEntity.ok(Map.of("reply", reply));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("reply", e.getMessage()));
        }
    }

    public record ChatRequest(String message, List<ChatMessage> history) {
    }

}
