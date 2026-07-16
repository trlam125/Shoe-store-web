package com.example.lshoestore.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.lshoestore.service.CartService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/ai")
public class AiController {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp");
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private final String aiServiceUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CartService cart;

    public AiController(@Value("${ai.service.url:http://localhost:8001}") String aiServiceUrl,
                        @Value("${ai.service.image-timeout-ms:120000}") int timeoutMs,
                        ObjectMapper objectMapper,
                        CartService cart) {
        this.aiServiceUrl = aiServiceUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.cart = cart;

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
            @RequestParam(defaultValue = "6") int limit) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Vui lòng chọn một ảnh."));
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            return ResponseEntity.status(413).body(Map.of("detail", "Ảnh tải lên vượt quá 10 MB."));
        }
        if (!ALLOWED_IMAGE_TYPES.contains(file.getContentType())) {
            return ResponseEntity.status(415)
                    .body(Map.of("detail", "Chỉ hỗ trợ ảnh JPEG, PNG hoặc WebP."));
        }

        int safeLimit = Math.max(1, Math.min(limit, 12));
        try {
            ByteArrayResource image = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return "shoe-image";
                }
            };
            HttpHeaders imageHeaders = new HttpHeaders();
            imageHeaders.setContentType(MediaType.parseMediaType(file.getContentType()));

            MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
            multipart.add("file", new HttpEntity<>(image, imageHeaders));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            return restTemplate.exchange(
                    aiServiceUrl + "/dl/image-search?limit=" + safeLimit,
                    HttpMethod.POST,
                    new HttpEntity<>(multipart, headers),
                    new ParameterizedTypeReference<>() {
                    });
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(readError(e.getResponseBodyAsString()));
        } catch (ResourceAccessException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "detail", "Không kết nối được AI service tại cổng 8001."));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Không đọc được ảnh tải lên."));
        }
    }

    private Map<String, Object> readError(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of("detail", "AI service không thể xử lý ảnh.");
        }
    }
}
