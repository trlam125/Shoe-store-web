package com.example.lshoestore.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Proxy nhận request từ browser, forward sang FastAPI chatbot (port 8000).
 * Tránh CORS issue khi browser gọi thẳng sang port khác.
 */
@RestController
@RequestMapping("/chatbot")
public class ChatbotController {

    private static final String CHATBOT_API_URL = "http://localhost:8000/api/chat";
    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/chat")
    public ResponseEntity<Map> chat(@RequestBody Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    CHATBOT_API_URL, request, Map.class);
            return ResponseEntity.ok(response.getBody());
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "reply", "Trợ lý đang tạm thời không khả dụng. Vui lòng thử lại sau."));
        }
    }
}
