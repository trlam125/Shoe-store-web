package com.example.lshoestore.config;

import com.example.lshoestore.model.Category;
import com.example.lshoestore.model.Product;
import com.example.lshoestore.model.ProductVariant;
import com.example.lshoestore.model.User;
import com.example.lshoestore.repository.CategoryRepository;
import com.example.lshoestore.repository.ProductRepository;
import com.example.lshoestore.repository.UserRepository;
import com.example.lshoestore.service.PasswordPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Configuration
public class DataSeeder {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final Map<String, String> DEMO_SHOE_IMAGES_BY_BRAND = Map.of(
            "Nike", "/images/products/catalog-nike.png",
            "Adidas", "/images/products/catalog-adidas.png",
            "New Balance", "/images/products/catalog-new-balance.png",
            "Puma", "/images/products/catalog-puma.png",
            "MLB", "/images/products/catalog-mlb.png",
            "Converse", "/images/products/catalog-converse.png",
            "Vans", "/images/products/catalog-vans.png",
            "Asics", "/images/products/catalog-asics.png"
    );


    @Bean
    CommandLineRunner seed(UserRepository users,
                           CategoryRepository categories,
                           ProductRepository products,
                           PasswordEncoder encoder,
                           @Value("${app.bootstrap-admin.email:}") String adminEmail,
                           @Value("${app.bootstrap-admin.password:}") String adminPassword,
                           @Value("${app.seed-demo-data:false}") boolean seedDemoData) {
        return args -> {
            users.findByEmailIgnoreCase("lam@gmail.com")
                    .filter(user -> encoder.matches("admin123", user.getPassword()))
                    .ifPresent(user -> {
                        String replacementPassword = isUsableBootstrapPassword(adminPassword)
                                ? adminPassword
                                : UUID.randomUUID().toString();
                        user.setPassword(encoder.encode(replacementPassword));
                        user.revokeSessions();
                        users.save(user);
                        log.warn("Disabled the legacy default password for account {}. Configure BOOTSTRAP_ADMIN_PASSWORD or use password reset.", user.getEmail());
                    });

            if (!users.existsByRoleIgnoreCase("ROLE_ADMIN")
                    && adminEmail != null && !adminEmail.isBlank()
                    && isUsableBootstrapPassword(adminPassword)) {
                String normalizedAdminEmail = adminEmail.trim().toLowerCase(java.util.Locale.ROOT);
                User admin = users.findByEmailIgnoreCase(normalizedAdminEmail).orElseGet(User::new);
                boolean existingAccount = admin.getId() != null;
                if (!existingAccount) {
                    admin.setFullName("LSHOE Administrator");
                    admin.setEmail(normalizedAdminEmail);
                }
                admin.setPassword(encoder.encode(adminPassword));
                admin.setRole("ROLE_ADMIN");
                if (existingAccount) admin.revokeSessions();
                users.save(admin);
                log.info("Bootstrapped administrator account {}", normalizedAdminEmail);
            }

            String[][] categoryData = {
                    {"Giày Nike", "Sneaker Nike nam nữ, dễ phối đồ và phù hợp sử dụng hằng ngày"},
                    {"Giày Adidas", "Giày Adidas phong cách thể thao, tối giản và năng động"},
                    {"Giày New Balance", "Giày đi học, đi chơi, đi bộ nhiều vẫn êm chân"},
                    {"Giày Puma", "Giày thể thao trẻ trung, giá tốt và dễ mang"},
                    {"Giày MLB", "Giày đế cao phong cách Hàn Quốc, nổi bật khi phối đồ"},
                    {"Giày Converse", "Giày vải cổ thấp, cổ cao và phong cách streetwear"},
                    {"Giày Vans", "Giày skate basic, bền và hợp nhiều phong cách"},
                    {"Giày Asics", "Giày chạy bộ và sneaker retro êm chân"}
            };

            // Danh mục là dữ liệu nền bắt buộc vì giao diện hiện chưa có trang tạo danh mục.
            // Sản phẩm minh họa vẫn chỉ được thêm khi SEED_DEMO_DATA=true.
            for (String[] item : categoryData) {
                categories.findByName(item[0]).orElseGet(() -> categories.save(new Category(item[0], item[1])));
            }

            if (!seedDemoData) {
                return;
            }

            if (products.count() == 0) {
                Map<String, Category> categoryMap = new HashMap<>();
                categories.findAll().forEach(category -> categoryMap.put(category.getName(), category));

                add(products, categoryMap, "Nike Air Force 1 Trắng Cổ Điển", "Nike", "Giày trắng basic dễ phối đồ, phù hợp đi học, đi chơi và đi làm.", 1890000, 2290000, "Giày Nike", 35);
                add(products, categoryMap, "Nike Air Jordan 1 Low Panda", "Nike", "Thiết kế đen trắng nổi bật, form ôm chân, đế bám tốt.", 2490000, 2990000, "Giày Nike", 18);
                add(products, categoryMap, "Nike Dunk Low Grey Fog", "Nike", "Tông xám trắng thanh lịch, hợp nhiều phong cách streetwear.", 2690000, 3190000, "Giày Nike", 15);
                add(products, categoryMap, "Nike Air Max 90 Triple White", "Nike", "Đệm Air êm, dáng thể thao, phù hợp di chuyển cả ngày.", 2790000, 3290000, "Giày Nike", 12);
                add(products, categoryMap, "Nike Court Vision Low Black", "Nike", "Màu đen khỏe khoắn, dễ vệ sinh, phù hợp sử dụng hằng ngày.", 1590000, 1890000, "Giày Nike", 28);
                add(products, categoryMap, "Nike Blazer Mid 77 Vintage", "Nike", "Cổ cao vintage, phù hợp phối quần jean và áo khoác.", 2290000, 2690000, "Giày Nike", 16);
                add(products, categoryMap, "Nike Pegasus 41 Black White", "Nike", "Giày chạy bộ nhẹ, đệm êm và thông thoáng.", 2890000, 3490000, "Giày Nike", 10);
                add(products, categoryMap, "Nike Cortez White Red Blue", "Nike", "Mẫu retro kinh điển, form gọn và dễ phối đồ.", 1990000, 2390000, "Giày Nike", 20);

                add(products, categoryMap, "Adidas Samba OG Cloud White", "Adidas", "Mẫu Samba hot trend, chất da mềm, kiểu dáng gọn gàng.", 2590000, 2990000, "Giày Adidas", 20);
                add(products, categoryMap, "Adidas Campus 00s Grey", "Adidas", "Dáng chunky nhẹ, màu xám dễ phối với quần jean và quần ống rộng.", 2390000, 2790000, "Giày Adidas", 22);
                add(products, categoryMap, "Adidas Superstar Trắng Sọc Đen", "Adidas", "Thiết kế mũi sò biểu tượng, bền và dễ mang.", 1890000, 2290000, "Giày Adidas", 30);
                add(products, categoryMap, "Adidas Stan Smith Green", "Adidas", "Giày tennis cổ điển, tối giản, hợp phong cách sạch sẽ.", 1790000, 2190000, "Giày Adidas", 27);
                add(products, categoryMap, "Adidas Forum Low White Blue", "Adidas", "Phối màu trắng xanh trẻ trung, quai dán cá tính.", 2290000, 2690000, "Giày Adidas", 16);
                add(products, categoryMap, "Adidas Gazelle Indoor Blue", "Adidas", "Chất da lộn mềm, phối màu xanh nổi bật.", 2490000, 2890000, "Giày Adidas", 13);
                add(products, categoryMap, "Adidas Ultraboost Light Black", "Adidas", "Đệm Boost êm, phù hợp chạy bộ và đi lại nhiều.", 3290000, 3890000, "Giày Adidas", 9);
                add(products, categoryMap, "Adidas Spezial Brown Gum", "Adidas", "Phong cách terrace cổ điển, đế gum bền và đẹp.", 2390000, 2790000, "Giày Adidas", 14);

                add(products, categoryMap, "New Balance 550 White Green", "New Balance", "Form retro bóng rổ, phối màu trắng xanh nổi bật.", 2590000, 3190000, "Giày New Balance", 14);
                add(products, categoryMap, "New Balance 530 Silver Navy", "New Balance", "Đệm êm, phong cách dad shoes, phù hợp đi bộ nhiều.", 2190000, 2590000, "Giày New Balance", 26);
                add(products, categoryMap, "New Balance 574 Classic Grey", "New Balance", "Mẫu cổ điển màu xám, nhẹ và bền, dễ phối đồ.", 1890000, 2290000, "Giày New Balance", 24);
                add(products, categoryMap, "New Balance 327 Moonbeam", "New Balance", "Thiết kế retro, đế răng cưa cá tính, màu kem thanh lịch.", 2290000, 2790000, "Giày New Balance", 19);
                add(products, categoryMap, "New Balance 2002R Protection Pack", "New Balance", "Dáng chunky cao cấp, phối màu bụi và cá tính.", 3290000, 3890000, "Giày New Balance", 8);
                add(products, categoryMap, "New Balance 9060 Sea Salt", "New Balance", "Đệm dày êm chân, dáng hiện đại và nổi bật.", 3490000, 4090000, "Giày New Balance", 7);
                add(products, categoryMap, "New Balance 1906R Metallic", "New Balance", "Phong cách running retro, thoáng khí và nhẹ chân.", 2890000, 3390000, "Giày New Balance", 11);
                add(products, categoryMap, "New Balance 990v5 Grey", "New Balance", "Form cao cấp, phối màu xám kinh điển, rất êm khi đi lâu.", 4290000, 4990000, "Giày New Balance", 5);

                add(products, categoryMap, "Puma Suede Classic Black White", "Puma", "Chất liệu da lộn mềm, kiểu dáng cổ điển không lỗi mốt.", 1590000, 1990000, "Giày Puma", 32);
                add(products, categoryMap, "Puma Palermo Vintage Green", "Puma", "Phối màu xanh vintage, phù hợp phong cách casual.", 1890000, 2290000, "Giày Puma", 21);
                add(products, categoryMap, "Puma RS-X Reinvention", "Puma", "Dáng thể thao chunky, đệm êm, nổi bật khi xuống phố.", 2190000, 2690000, "Giày Puma", 13);
                add(products, categoryMap, "Puma Slipstream Lo White", "Puma", "Phong cách bóng rổ cổ điển, da mềm và chắc chân.", 1990000, 2390000, "Giày Puma", 17);
                add(products, categoryMap, "Puma Carina Street Pink", "Puma", "Màu hồng nhẹ nhàng, phù hợp phối đồ nữ tính.", 1490000, 1890000, "Giày Puma", 23);
                add(products, categoryMap, "Puma Future Rider Play On", "Puma", "Màu sắc trẻ trung, nhẹ và êm cho hoạt động hằng ngày.", 1790000, 2190000, "Giày Puma", 18);

                add(products, categoryMap, "MLB Chunky Liner New York Yankees", "MLB", "Giày đế cao hack dáng, logo NY cá tính.", 2290000, 2790000, "Giày MLB", 17);
                add(products, categoryMap, "MLB Big Ball Chunky Boston Red Sox", "MLB", "Thiết kế Hàn Quốc nổi bật, phù hợp phối đồ streetwear.", 2390000, 2890000, "Giày MLB", 11);
                add(products, categoryMap, "MLB Playball Origin LA Dodgers", "MLB", "Dáng thấp nhẹ chân, màu trắng xanh dễ mang.", 1590000, 1890000, "Giày MLB", 25);
                add(products, categoryMap, "MLB Chunky Classic Monogram", "MLB", "Họa tiết monogram nổi bật, đế cao và chắc chắn.", 2690000, 3190000, "Giày MLB", 9);
                add(products, categoryMap, "MLB Big Ball Chunky A New York", "MLB", "Logo lớn cá tính, tạo điểm nhấn cho outfit.", 2490000, 2990000, "Giày MLB", 12);
                add(products, categoryMap, "MLB Runner Basic Black", "MLB", "Màu đen dễ đi, form thể thao và nhẹ chân.", 1890000, 2290000, "Giày MLB", 18);

                add(products, categoryMap, "Converse Chuck Taylor 1970s High Black", "Converse", "Giày vải cổ cao huyền thoại, dễ phối nhiều phong cách.", 1690000, 1990000, "Giày Converse", 29);
                add(products, categoryMap, "Converse Chuck Taylor 1970s Low Parchment", "Converse", "Màu kem cổ thấp, đơn giản, phù hợp đi học và đi chơi.", 1590000, 1890000, "Giày Converse", 34);
                add(products, categoryMap, "Converse Run Star Hike Black", "Converse", "Đế răng cưa cá tính, tăng chiều cao, nổi bật khi phối đồ.", 2190000, 2590000, "Giày Converse", 10);
                add(products, categoryMap, "Converse One Star Pro Suede", "Converse", "Da lộn mềm, biểu tượng ngôi sao, phong cách skate.", 1790000, 2190000, "Giày Converse", 23);
                add(products, categoryMap, "Converse Chuck 70 Plus Canvas", "Converse", "Thiết kế biến tấu hiện đại, cá tính và khác biệt.", 2090000, 2490000, "Giày Converse", 12);
                add(products, categoryMap, "Converse Weapon CX White", "Converse", "Phong cách bóng rổ cổ điển, đệm CX êm hơn.", 2290000, 2690000, "Giày Converse", 14);

                add(products, categoryMap, "Vans Old Skool Black White", "Vans", "Mẫu skate kinh điển, bền và cực dễ phối đồ.", 1490000, 1790000, "Giày Vans", 31);
                add(products, categoryMap, "Vans Authentic Classic Black", "Vans", "Form thấp basic, phù hợp đi học và đi chơi.", 1290000, 1590000, "Giày Vans", 28);
                add(products, categoryMap, "Vans Knu Skool Navy", "Vans", "Dáng phồng retro, dây giày bản to nổi bật.", 1890000, 2290000, "Giày Vans", 16);
                add(products, categoryMap, "Vans Sk8-Hi Black White", "Vans", "Cổ cao cá tính, hợp phong cách streetwear.", 1690000, 1990000, "Giày Vans", 19);
                add(products, categoryMap, "Vans Slip-On Checkerboard", "Vans", "Không dây tiện lợi, họa tiết caro biểu tượng.", 1390000, 1690000, "Giày Vans", 22);
                add(products, categoryMap, "Vans Era 95 DX Anaheim", "Vans", "Chất vải đẹp, form cổ điển và thoải mái.", 1590000, 1890000, "Giày Vans", 15);

                add(products, categoryMap, "Asics Gel-Kayano 14 Cream Black", "Asics", "Giày chạy bộ retro, đệm Gel êm và thoáng.", 3290000, 3890000, "Giày Asics", 9);
                add(products, categoryMap, "Asics Gel-1130 White Clay", "Asics", "Tông trắng be nhẹ nhàng, dễ phối đồ hằng ngày.", 2390000, 2890000, "Giày Asics", 13);
                add(products, categoryMap, "Asics Gel-NYC Graphite Grey", "Asics", "Form thể thao hiện đại, đế êm và chắc chân.", 2990000, 3490000, "Giày Asics", 8);
                add(products, categoryMap, "Asics Japan S White Black", "Asics", "Thiết kế tối giản, giá tốt, hợp nhiều độ tuổi.", 1490000, 1790000, "Giày Asics", 24);
                add(products, categoryMap, "Asics Gel-Lyte III Cream", "Asics", "Lưỡi gà tách đặc trưng, màu kem sang và dễ đi.", 2490000, 2990000, "Giày Asics", 10);
                add(products, categoryMap, "Asics Novablast 4 Black", "Asics", "Đệm nảy tốt, phù hợp chạy bộ và tập luyện.", 3190000, 3690000, "Giày Asics", 7);
            }
        };
    }


    private boolean isUsableBootstrapPassword(String value) {
        return PasswordPolicy.isValidForBcrypt(value) && !value.startsWith("CHANGE_ME");
    }

    private void add(ProductRepository repo,
                     Map<String, Category> categoryMap,
                     String name,
                     String brand,
                     String description,
                     int price,
                     int oldPrice,
                     String categoryName,
                     int stock) {
        Product product = new Product();
        product.setName(name);
        product.setBrand(brand);
        product.setDescription(description);
        product.setPrice(BigDecimal.valueOf(price));
        product.setOldPrice(BigDecimal.valueOf(oldPrice));
        product.setCategory(categoryMap.get(categoryName));
        String[] sizes = {"36", "37", "38", "39", "40", "41", "42", "43"};
        int baseStock = stock / sizes.length;
        int remainder = stock % sizes.length;
        for (int index = 0; index < sizes.length; index++) {
            product.addVariant(new ProductVariant(product, sizes[index],
                    baseStock + (index < remainder ? 1 : 0)));
        }
        product.syncInventorySummary();
        product.setImageUrl(DEMO_SHOE_IMAGES_BY_BRAND.get(brand));
        repo.save(product);
    }
}
