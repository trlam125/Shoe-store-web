package com.example.lshoestore.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lshoestore.service.CartService;
import com.example.lshoestore.service.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/ai")
public class AiController {
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp");
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private final String aiServiceUrl;
    private final String aiApiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CartService cart;
    private final RequestRateLimiter rateLimiter;
    private final int imageRequestsPerMinute;

    public AiController(@Value("${ai.service.url:http://127.0.0.1:8001}") String aiServiceUrl,
                        @Value("${ai.service.api-key:}") String aiApiKey,
                        @Value("${ai.service.image-timeout-ms:120000}") int timeoutMs,
                        @Value("${app.rate-limit.image-per-minute:5}") int imageRequestsPerMinute,
                        ObjectMapper objectMapper,
                        CartService cart,
                        RequestRateLimiter rateLimiter) {
        this.aiServiceUrl = aiServiceUrl.replaceAll("/+$", "");
        this.aiApiKey = aiApiKey == null ? "" : aiApiKey.trim();
        this.objectMapper = objectMapper;
        this.cart = cart;
        this.rateLimiter = rateLimiter;
        this.imageRequestsPerMinute = Math.max(imageRequestsPerMinute, 1);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @GetMapping("/image-search")
    public String imageSearch(Model model, Authentication auth, HttpSession session) {
        model.addAttribute("cartCount", cart.count(auth, session));
        return "ai/image-search";
    }

    @PostMapping("/image-search/analyze")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> analyzeImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "6") int limit,
            HttpServletRequest request,
            Authentication authentication) {
        if (!rateLimiter.allow("image", request, authentication,
                imageRequestsPerMinute, Duration.ofMinutes(1))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("detail", "Bạn đang gửi quá nhiều ảnh. Vui lòng thử lại sau."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Vui lòng chọn một ảnh."));
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            return ResponseEntity.status(413).body(Map.of("detail", "Ảnh tải lên vượt quá 10 MB."));
        }
        if (!ALLOWED_IMAGE_TYPES.contains(file.getContentType())) {
            return ResponseEntity.status(415).body(Map.of("detail", "Chỉ hỗ trợ JPEG, PNG hoặc WebP."));
        }

        int safeLimit = Math.max(1, Math.min(limit, 12));
        try {
            ByteArrayResource image = new ByteArrayResource(file.getBytes()) {
                @Override public String getFilename() { return "shoe-image"; }
            };
            HttpHeaders imageHeaders = new HttpHeaders();
            imageHeaders.setContentType(MediaType.parseMediaType(file.getContentType()));
            MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
            multipart.add("file", new HttpEntity<>(image, imageHeaders));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            if (!aiApiKey.isBlank()) headers.set("X-Internal-API-Key", aiApiKey);
            return restTemplate.exchange(
                    aiServiceUrl + "/dl/image-search?limit=" + safeLimit,
                    HttpMethod.POST,
                    new HttpEntity<>(multipart, headers),
                    new ParameterizedTypeReference<>() {});
        } catch (HttpStatusCodeException exception) {
            return ResponseEntity.status(exception.getStatusCode())
                    .body(readError(exception.getResponseBodyAsString()));
        } catch (ResourceAccessException exception) {
            return ResponseEntity.status(503).body(Map.of("detail", "Không kết nối được dịch vụ AI."));
        } catch (IOException exception) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Không đọc được ảnh tải lên."));
        }
    }

    private Map<String, Object> readError(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of("detail", "Dịch vụ AI không thể xử lý ảnh.");
        }
    }
}
