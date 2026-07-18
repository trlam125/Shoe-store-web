package com.example.lshoestore.service;

import com.example.lshoestore.model.Product;
import com.example.lshoestore.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ChatbotService {
    private static final String BASE_SYSTEM_PROMPT = "Bạn là trợ lý tư vấn giày của LSHOE. "
            + "Hãy trả lời ngắn gọn, thân thiện bằng tiếng Việt. "
            + "Khi giới thiệu sản phẩm, chỉ được dùng đúng dữ liệu trong DANH MỤC HIỆN TẠI bên dưới. "
            + "Không tự bịa tên giày, giá, kích cỡ hoặc tồn kho. "
            + "Nếu danh mục không có sản phẩm phù hợp, hãy nói rõ và gợi ý khách thay đổi ngân sách, hãng hoặc nhu cầu. "
            + "Có thể tư vấn cách đo chân và phối đồ, nhưng phải nhắc khách chọn đúng size hiển thị trên trang sản phẩm.";
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final int MAX_CONTENT_LENGTH = 2_000;
    private static final int MAX_CATALOG_CHARACTERS = 14_000;

    private final RestTemplate restTemplate;
    private final ProductRepository products;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public ChatbotService(ProductRepository products,
                          @Value("${chatbot.api-url}") String apiUrl,
                          @Value("${chatbot.api-key:}") String apiKey,
                          @Value("${chatbot.model}") String model,
                          @Value("${chatbot.timeout-ms:30000}") int timeoutMs) {
        this.products = products;
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
        messages.add(Map.of("role", "system", "content", buildGroundedSystemPrompt()));
        appendValidHistory(messages, history);
        messages.add(Map.of("role", "user", "content", truncate(message)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.2);
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

    private String buildGroundedSystemPrompt() {
        List<Product> catalog = products.findTop80ByActiveTrueAndStockGreaterThanOrderByIdDesc(0);
        StringBuilder context = new StringBuilder(BASE_SYSTEM_PROMPT)
                .append("\n\nDANH MỤC HIỆN TẠI (dữ liệu từ hệ thống LSHOE):\n");
        if (catalog.isEmpty()) {
            return context.append("Hiện không có sản phẩm còn hàng. Không được giới thiệu sản phẩm cụ thể.").toString();
        }

        NumberFormat money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        for (Product product : catalog) {
            String line = "- #" + product.getId()
                    + " | " + safe(product.getName())
                    + " | hãng: " + safe(product.getBrand())
                    + " | giá: " + money.format(product.getPrice()) + "đ"
                    + " | size: " + String.join(", ", product.getAvailableSizes())
                    + " | tồn: " + product.getStock()
                    + " | danh mục: " + (product.getCategory() == null ? "Khác" : safe(product.getCategory().getName()))
                    + "\n";
            if (context.length() + line.length() > MAX_CATALOG_CHARACTERS) break;
            context.append(line);
        }
        context.append("Khi khách hỏi giá hoặc tồn kho, dùng đúng con số trên. Không suy đoán dữ liệu ngoài danh mục.");
        return context.toString();
    }

    private String safe(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
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
