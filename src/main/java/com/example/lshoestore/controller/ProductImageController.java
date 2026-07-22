package com.example.lshoestore.controller;

import com.example.lshoestore.service.ProductImageStorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Duration;

@Controller
public class ProductImageController {
    private final ProductImageStorageService imageStorage;

    public ProductImageController(ProductImageStorageService imageStorage) {
        this.imageStorage = imageStorage;
    }

    @GetMapping("/uploads/products/{filename:.+}")
    public ResponseEntity<Resource> image(@PathVariable String filename) {
        Resource resource = imageStorage.load(filename);
        if (resource == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(imageStorage.contentType(filename)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .body(resource);
    }
}
