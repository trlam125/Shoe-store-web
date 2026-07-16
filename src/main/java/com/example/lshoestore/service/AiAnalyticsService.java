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

    public AiAnalyticsService(ObjectMapper objectMapper,
                              @Value("${ai.service.url:http://localhost:8001}") String aiServiceUrl) {
        this.objectMapper = objectMapper;
        this.aiServiceUrl = aiServiceUrl;
    }

    public Map<String, Object> getCustomerSegments() {
        return getJson("/ml/customer-segments");
    }

    public Map<String, Object> getSalesForecast() {
        return getJson("/ml/sales-forecast?days=7");
    }

    private Map<String, Object> getJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(aiServiceUrl + path))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("AI service request was interrupted: {}", path);
            return Collections.emptyMap();
        } catch (Exception e) {
            log.warn("AI service request failed for {}: {}", path, e.getMessage());
            return Collections.emptyMap();
        }
    }
}
