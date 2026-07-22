package com.example.lshoestore.service;

import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductImageStorageService {
    public static final String PUBLIC_PREFIX = "/uploads/products/";
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final Path uploadDirectory;
    private final ProductRepository products;

    public ProductImageStorageService(
            @Value("${app.product-image.upload-dir:uploads/products}") String uploadDirectory,
            ProductRepository products) {
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
        this.products = products;
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException("Ảnh sản phẩm không được vượt quá 5 MB.", "image_too_large");
        }

        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException("Chỉ hỗ trợ ảnh JPEG, PNG hoặc WebP.", "invalid_image_type");
        }

        try {
            byte[] header = file.getInputStream().readNBytes(12);
            String extension = detectExtension(header);
            if (extension == null || !contentTypeMatches(contentType, extension)) {
                throw new BusinessException("Nội dung tệp không phải ảnh JPEG, PNG hoặc WebP hợp lệ.",
                        "invalid_image_content");
            }

            Files.createDirectories(uploadDirectory);
            String filename = UUID.randomUUID() + "." + extension;
            Path target = uploadDirectory.resolve(filename).normalize();
            if (!target.getParent().equals(uploadDirectory)) {
                throw new BusinessException("Tên tệp ảnh không hợp lệ.", "invalid_image_name");
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return PUBLIC_PREFIX + filename;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException("Không thể lưu ảnh sản phẩm. Vui lòng thử lại.", "image_store_failed");
        }
    }

    public Resource load(String filename) {
        if (filename == null || !filename.matches("[0-9a-fA-F-]{36}\\.(jpg|png|webp)")) return null;
        try {
            Path file = uploadDirectory.resolve(filename).normalize();
            if (!file.getParent().equals(uploadDirectory) || !Files.isRegularFile(file)) return null;
            Resource resource = new UrlResource(file.toUri());
            return resource.isReadable() ? resource : null;
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * Deletes an old managed upload only when no product row still references it.
     * This protects intentionally shared image URLs when one product is edited.
     */
    public void deleteManagedIfUnreferenced(String imageUrl) {
        if (!isManagedImageUrl(imageUrl)) return;
        try {
            if (products.existsByImageUrl(imageUrl)) return;
        } catch (RuntimeException ignored) {
            // Image cleanup is best-effort. A temporary database error must never turn a
            // successfully saved product into a broken record or delete its new upload.
            return;
        }
        deleteManaged(imageUrl);
    }

    public void deleteManaged(String imageUrl) {
        if (!isManagedImageUrl(imageUrl)) return;
        String filename = imageUrl.substring(PUBLIC_PREFIX.length());
        try {
            Path file = uploadDirectory.resolve(filename).normalize();
            if (file.getParent().equals(uploadDirectory)) Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A missing old image must not roll back an otherwise valid product update.
        }
    }

    private boolean isManagedImageUrl(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(PUBLIC_PREFIX)) return false;
        String filename = imageUrl.substring(PUBLIC_PREFIX.length());
        return filename.matches("[0-9a-fA-F-]{36}\\.(jpg|png|webp)");
    }

    public String contentType(String filename) {
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private String detectExtension(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "jpg";
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d
                && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) return "png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W'
                && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "webp";
        return null;
    }

    private boolean contentTypeMatches(String contentType, String extension) {
        return ("jpg".equals(extension) && "image/jpeg".equals(contentType))
                || ("png".equals(extension) && "image/png".equals(contentType))
                || ("webp".equals(extension) && "image/webp".equals(contentType));
    }
}
