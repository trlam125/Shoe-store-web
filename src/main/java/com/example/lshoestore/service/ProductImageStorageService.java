package com.example.lshoestore.service;

import com.example.lshoestore.exception.BusinessException;
import com.example.lshoestore.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductImageStorageService {
    public static final String PUBLIC_PREFIX = "/uploads/products/";
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final Path uploadDirectory;
    private final ProductRepository products;
    private final JdbcTemplate jdbcTemplate;
    private final StorageMode storageMode;
    private final long maxImageBytes;

    public ProductImageStorageService(
            @Value("${app.product-image.upload-dir:uploads/products}") String uploadDirectory,
            @Value("${app.product-image.storage:filesystem}") String storageMode,
            @Value("${app.product-image.max-bytes:5242880}") long maxImageBytes,
            ProductRepository products,
            JdbcTemplate jdbcTemplate) {
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
        this.products = products;
        this.jdbcTemplate = jdbcTemplate;
        this.storageMode = StorageMode.from(storageMode);
        this.maxImageBytes = Math.max(maxImageBytes, 1L);
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        if (file.getSize() > maxImageBytes) {
            throw new BusinessException("Ảnh sản phẩm vượt quá giới hạn dung lượng cho phép.", "image_too_large");
        }

        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException("Chỉ hỗ trợ ảnh JPEG, PNG hoặc WebP.", "invalid_image_type");
        }

        try {
            byte[] payload = file.getBytes();
            String extension = detectExtension(payload);
            if (extension == null || !contentTypeMatches(contentType, extension)) {
                throw new BusinessException("Nội dung tệp không phải ảnh JPEG, PNG hoặc WebP hợp lệ.",
                        "invalid_image_content");
            }

            String filename = UUID.randomUUID() + "." + extension;
            if (storageMode == StorageMode.DATABASE) {
                storeInDatabase(filename, contentType, payload);
            } else {
                storeOnFilesystem(filename, payload);
            }
            return PUBLIC_PREFIX + filename;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | DataAccessException exception) {
            throw new BusinessException("Không thể lưu ảnh sản phẩm. Vui lòng thử lại.", "image_store_failed");
        }
    }

    public StoredImage load(String filename) {
        if (!isValidFilename(filename)) return null;
        return storageMode == StorageMode.DATABASE
                ? loadFromDatabase(filename)
                : loadFromFilesystem(filename);
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
        if (storageMode == StorageMode.DATABASE) {
            try {
                jdbcTemplate.update("DELETE FROM product_image_asset WHERE filename = ?", filename);
            } catch (DataAccessException ignored) {
                // Cleanup is best-effort and must not roll back a product update.
            }
            return;
        }

        try {
            Path file = uploadDirectory.resolve(filename).normalize();
            if (file.getParent().equals(uploadDirectory)) Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A missing old image must not roll back an otherwise valid product update.
        }
    }

    private void storeOnFilesystem(String filename, byte[] payload) throws IOException {
        Files.createDirectories(uploadDirectory);
        Path target = uploadDirectory.resolve(filename).normalize();
        if (!target.getParent().equals(uploadDirectory)) {
            throw new BusinessException("Tên tệp ảnh không hợp lệ.", "invalid_image_name");
        }
        Files.write(target, payload);
    }

    private void storeInDatabase(String filename, String contentType, byte[] payload) {
        jdbcTemplate.update(
                "INSERT INTO product_image_asset(filename, content_type, data) VALUES (?, ?, ?)",
                filename, contentType, payload
        );
    }

    private StoredImage loadFromFilesystem(String filename) {
        try {
            Path file = uploadDirectory.resolve(filename).normalize();
            if (!file.getParent().equals(uploadDirectory) || !Files.isRegularFile(file)) return null;
            byte[] data = Files.readAllBytes(file);
            return new StoredImage(new ByteArrayResource(data), contentTypeForFilename(filename));
        } catch (IOException exception) {
            return null;
        }
    }

    private StoredImage loadFromDatabase(String filename) {
        try {
            List<StoredImage> results = jdbcTemplate.query(
                    "SELECT content_type, data FROM product_image_asset WHERE filename = ?",
                    (rs, rowNum) -> new StoredImage(
                            new ByteArrayResource(rs.getBytes("data")),
                            rs.getString("content_type")
                    ),
                    filename
            );
            return results.isEmpty() ? null : results.getFirst();
        } catch (DataAccessException exception) {
            return null;
        }
    }

    private boolean isManagedImageUrl(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(PUBLIC_PREFIX)) return false;
        return isValidFilename(imageUrl.substring(PUBLIC_PREFIX.length()));
    }

    private boolean isValidFilename(String filename) {
        return filename != null && filename.matches("[0-9a-fA-F-]{36}\\.(jpg|png|webp)");
    }

    private String contentTypeForFilename(String filename) {
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

    public record StoredImage(Resource resource, String contentType) {}

    private enum StorageMode {
        FILESYSTEM,
        DATABASE;

        static StorageMode from(String value) {
            return "database".equalsIgnoreCase(value == null ? "" : value.trim())
                    ? DATABASE
                    : FILESYSTEM;
        }
    }
}
