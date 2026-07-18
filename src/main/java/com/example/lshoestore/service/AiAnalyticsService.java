package com.example.lshoestore.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

@Service
public class AiAnalyticsService {
    private static final Logger log = LoggerFactory.getLogger(AiAnalyticsService.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper objectMapper;
    private final String aiServiceUrl;
    private final String apiKey;

    public AiAnalyticsService(ObjectMapper objectMapper,
                              @Value("${ai.service.url:http://127.0.0.1:8001}") String aiServiceUrl,
                              @Value("${ai.service.api-key:}") String apiKey) {
        this.objectMapper = objectMapper;
        this.aiServiceUrl = aiServiceUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public Map<String, Object> getCustomerSegments() { return getJson("/ml/customer-segments"); }
    public Map<String, Object> getSalesForecast() { return getJson("/ml/sales-forecast?days=7"); }

    private Map<String, Object> getJson(String path) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(aiServiceUrl + path))
                    .timeout(Duration.ofSeconds(8)).GET();
            if (!apiKey.isBlank()) builder.header("X-Internal-API-Key", apiKey);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return Collections.emptyMap();
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Collections.emptyMap();
        } catch (Exception exception) {
            log.warn("AI service request failed for {}: {}", path, exception.getMessage());
            return Collections.emptyMap();
        }
    }
}
