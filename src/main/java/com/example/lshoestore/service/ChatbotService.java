package com.example.lshoestore.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatbotService {
    private static final String SYSTEM_PROMPT = "Bạn là trợ lý tư vấn giày của LSHOE. "
            + "Hãy giúp khách hàng chọn giày, tư vấn kích cỡ và phối đồ. "
            + "Trả lời ngắn gọn, thân thiện bằng tiếng Việt.";
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final int MAX_CONTENT_LENGTH = 2_000;

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public ChatbotService(@Value("${chatbot.api-url}") String apiUrl,
                          @Value("${chatbot.api-key:}") String apiKey,
                          @Value("${chatbot.model}") String model,
                          @Value("${chatbot.timeout-ms:30000}") int timeoutMs) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    public String chat(String message, List<ChatMessage> history) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Chatbot chưa được cấu hình NVIDIA_API_KEY.");
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        appendValidHistory(messages, history);
        messages.add(Map.of("role", "user", "content", truncate(message)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.7);
        body.put("max_tokens", 1024);
        body.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        try {
            Map<?, ?> response = restTemplate.postForObject(apiUrl, new HttpEntity<>(body, headers), Map.class);
            return extractReply(response);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Trợ lý AI hiện không kết nối được.", exception);
        }
    }

    private void appendValidHistory(List<Map<String, String>> messages, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return;
        int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (int index = start; index < history.size(); index++) {
            ChatMessage item = history.get(index);
            if (item == null || item.content() == null || item.content().isBlank()) continue;
            if (!"user".equals(item.role()) && !"assistant".equals(item.role())) continue;
            messages.add(Map.of("role", item.role(), "content", truncate(item.content())));
        }
    }

    private String truncate(String value) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.length() <= MAX_CONTENT_LENGTH
                ? cleaned : cleaned.substring(0, MAX_CONTENT_LENGTH);
    }

    private String extractReply(Map<?, ?> response) {
        if (response != null && response.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.getFirst() instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> responseMessage
                && responseMessage.get("content") instanceof String content
                && !content.isBlank()) {
            return content;
        }
        throw new IllegalStateException("Trợ lý AI trả về dữ liệu không hợp lệ.");
    }

    public record ChatMessage(String role, String content) {}
}
