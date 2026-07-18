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

import java.text.Normalizer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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

    private final RestTemplate restTemplate;
    private final ProductRepository products;
    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final int emptyResponseRetries;
    private final int maxCatalogCharacters;

    public ChatbotService(ProductRepository products,
                          @Value("${chatbot.api-url}") String apiUrl,
                          @Value("${chatbot.api-key:}") String apiKey,
                          @Value("${chatbot.model}") String model,
                          @Value("${chatbot.timeout-ms:30000}") int timeoutMs,
                          @Value("${chatbot.max-tokens:4096}") int maxTokens,
                          @Value("${chatbot.empty-response-retries:1}") int emptyResponseRetries,
                          @Value("${chatbot.catalog-max-characters:40000}") int maxCatalogCharacters) {
        this.products = products;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = Math.max(1_024, Math.min(maxTokens, 16_384));
        this.emptyResponseRetries = Math.max(0, Math.min(emptyResponseRetries, 2));
        this.maxCatalogCharacters = Math.max(5_000, Math.min(maxCatalogCharacters, 120_000));
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
        messages.add(Map.of("role", "system", "content", buildGroundedSystemPrompt(message)));
        appendValidHistory(messages, history);
        messages.add(Map.of("role", "user", "content", truncate(message)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        try {
            for (int attempt = 0; attempt <= emptyResponseRetries; attempt++) {
                Map<?, ?> response = restTemplate.postForObject(
                        apiUrl, new HttpEntity<>(body, headers), Map.class);
                String reply = extractReply(response);
                if (reply != null) {
                    return reply;
                }
            }
            throw new IllegalStateException("Trợ lý AI chưa tạo được câu trả lời. Vui lòng thử lại.");
        } catch (RestClientException exception) {
            throw new IllegalStateException("Trợ lý AI hiện không kết nối được.", exception);
        }
    }

    private String buildGroundedSystemPrompt(String userMessage) {
        List<Product> catalog = prioritizeCatalog(
                products.findByActiveTrueAndStockGreaterThanOrderByIdDesc(0), userMessage);
        StringBuilder context = new StringBuilder(BASE_SYSTEM_PROMPT)
                .append("\n\nDANH MỤC HIỆN TẠI (dữ liệu từ hệ thống LSHOE):\n");
        if (catalog.isEmpty()) {
            return context.append("Hiện không có sản phẩm còn hàng. Không được giới thiệu sản phẩm cụ thể.").toString();
        }

        NumberFormat money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        int includedProducts = 0;
        for (Product product : catalog) {
            String line = "- #" + product.getId()
                    + " | " + safe(product.getName())
                    + " | hãng: " + safe(product.getBrand())
                    + " | giá: " + money.format(product.getPrice()) + "đ"
                    + " | size: " + String.join(", ", product.getAvailableSizes())
                    + " | tồn: " + product.getStock()
                    + " | danh mục: " + (product.getCategory() == null ? "Khác" : safe(product.getCategory().getName()))
                    + "\n";
            if (context.length() + line.length() > maxCatalogCharacters) break;
            context.append(line);
            includedProducts++;
        }
        if (includedProducts < catalog.size()) {
            context.append("Danh mục lớn nên ngữ cảnh đang hiển thị ")
                    .append(includedProducts).append("/").append(catalog.size())
                    .append(" sản phẩm phù hợp nhất với câu hỏi hiện tại. ");
        }
        context.append("Khi khách hỏi giá hoặc tồn kho, dùng đúng con số trên. Không suy đoán dữ liệu ngoài danh mục.");
        return context.toString();
    }

    private List<Product> prioritizeCatalog(List<Product> catalog, String userMessage) {
        String normalizedMessage = normalizeSearchText(userMessage);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalizedMessage.split("[^a-z0-9]+")) {
            if (token.length() >= 2) tokens.add(token);
        }
        return catalog.stream()
                .sorted(Comparator
                        .comparingInt((Product product) -> relevanceScore(product, normalizedMessage, tokens))
                        .reversed()
                        .thenComparing(Product::getId, Comparator.reverseOrder()))
                .toList();
    }

    private int relevanceScore(Product product,
                               String normalizedMessage,
                               LinkedHashSet<String> tokens) {
        String name = normalizeSearchText(product.getName());
        String brand = normalizeSearchText(product.getBrand());
        String category = product.getCategory() == null
                ? "" : normalizeSearchText(product.getCategory().getName());
        String description = normalizeSearchText(product.getDescription());
        int score = !name.isBlank() && normalizedMessage.contains(name) ? 30 : 0;
        for (String token : tokens) {
            if (name.contains(token)) score += 8;
            if (brand.contains(token)) score += 6;
            if (category.contains(token)) score += 4;
            if (description.contains(token)) score += 1;
        }
        return score;
    }

    private String normalizeSearchText(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replace('\n', ' ').replace('\r', ' ').trim();
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
        if (response == null) return null;

        String outputText = textValue(response.get("output_text"));
        if (outputText != null) return outputText;

        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()
                || !(choices.getFirst() instanceof Map<?, ?> choice)) {
            return null;
        }

        if (choice.get("message") instanceof Map<?, ?> responseMessage) {
            String messageText = textValue(responseMessage.get("content"));
            if (messageText != null) return messageText;
        }
        return textValue(choice.get("text"));
    }

    private String textValue(Object value) {
        if (value instanceof String text) {
            String cleaned = text.trim();
            return cleaned.isEmpty() ? null : cleaned;
        }
        if (!(value instanceof List<?> parts)) return null;

        StringBuilder combined = new StringBuilder();
        for (Object part : parts) {
            String text = null;
            if (part instanceof String stringPart) {
                text = stringPart;
            } else if (part instanceof Map<?, ?> block) {
                text = firstNonBlank(
                        textValue(block.get("text")),
                        textValue(block.get("content")),
                        textValue(block.get("value")));
            }
            if (text == null) continue;
            if (combined.length() > 0) combined.append('\n');
            combined.append(text);
        }
        String cleaned = combined.toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    public record ChatMessage(String role, String content) {}
}
