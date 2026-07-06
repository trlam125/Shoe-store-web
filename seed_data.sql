-- =============================================================
--  LSHOE Store — Seed data thủ công
--  Chạy script này SAU KHI ứng dụng đã khởi động ít nhất 1 lần
--  (để Hibernate tạo đủ các bảng qua ddl-auto=update).
--
--  Lệnh chạy:
--      mysql -u root -p lshoe_store < seed_data.sql
--  hoặc paste toàn bộ nội dung vào MySQL Workbench / DBeaver.
-- =============================================================

USE lshoe_store;

-- -------------------------------------------------------------
--  0. Dọn dữ liệu cũ (tuỳ chọn — bỏ comment nếu muốn reset)
-- -------------------------------------------------------------
-- SET FOREIGN_KEY_CHECKS = 0;
-- TRUNCATE TABLE saved_cart_items;
-- TRUNCATE TABLE order_item;
-- TRUNCATE TABLE orders;
-- TRUNCATE TABLE product;
-- TRUNCATE TABLE category;
-- TRUNCATE TABLE users;
-- SET FOREIGN_KEY_CHECKS = 1;

-- -------------------------------------------------------------
--  1. Admin account
--  Password: admin123  (BCrypt hash bên dưới)
-- -------------------------------------------------------------
INSERT IGNORE INTO users (full_name, email, password, role)
VALUES ('Quản trị viên LSHOE',
        'admin@lshoe.vn',
        '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.',
        'ROLE_ADMIN');

-- Nếu hash trên không khớp với BCryptPasswordEncoder của bạn,
-- hãy chạy ứng dụng một lần — DataSeeder sẽ tự tạo admin đúng hash.

-- -------------------------------------------------------------
--  2. Categories
-- -------------------------------------------------------------
INSERT IGNORE INTO category (name, description) VALUES
('Giày Nike',        'Sneaker Nike nam nữ, dễ phối đồ và phù hợp sử dụng hằng ngày'),
('Giày Adidas',      'Giày Adidas phong cách thể thao, tối giản và năng động'),
('Giày New Balance', 'Giày đi học, đi chơi, đi bộ nhiều vẫn êm chân'),
('Giày Puma',        'Giày thể thao trẻ trung, giá tốt và dễ mang'),
('Giày MLB',         'Giày đế cao phong cách Hàn Quốc, nổi bật khi phối đồ'),
('Giày Converse',    'Giày vải cổ thấp, cổ cao và phong cách streetwear'),
('Giày Vans',        'Giày skate basic, bền và hợp nhiều phong cách'),
('Giày Asics',       'Giày chạy bộ và sneaker retro êm chân');

-- -------------------------------------------------------------
--  3. Products
--  Dùng subquery để lấy category_id theo tên — không phụ thuộc
--  vào thứ tự AUTO_INCREMENT.
-- -------------------------------------------------------------
INSERT INTO product (name, brand, description, price, old_price, image_url, size_text, stock, active, category_id)
SELECT name, brand, description, price, old_price, image_url, size_text, stock, active,
       (SELECT id FROM category WHERE category.name = cat_name)
FROM (SELECT
    'Nike Air Force 1 Trắng Cổ Điển'             AS name, 'Nike'        AS brand,
    'Giày trắng basic dễ phối đồ, phù hợp đi học, đi chơi và đi làm.'      AS description,
    1890000 AS price, 2290000 AS old_price, 35 AS stock, 'Giày Nike' AS cat_name
UNION ALL SELECT 'Nike Air Jordan 1 Low Panda','Nike','Thiết kế đen trắng nổi bật, form ôm chân, đế bám tốt.',2490000,2990000,18,'Giày Nike'
UNION ALL SELECT 'Nike Dunk Low Grey Fog','Nike','Tông xám trắng thanh lịch, hợp nhiều phong cách streetwear.',2690000,3190000,15,'Giày Nike'
UNION ALL SELECT 'Nike Air Max 90 Triple White','Nike','Đệm Air êm, dáng thể thao, phù hợp di chuyển cả ngày.',2790000,3290000,12,'Giày Nike'
UNION ALL SELECT 'Nike Court Vision Low Black','Nike','Màu đen khỏe khoắn, dễ vệ sinh, phù hợp sử dụng hằng ngày.',1590000,1890000,28,'Giày Nike'
UNION ALL SELECT 'Nike Blazer Mid 77 Vintage','Nike','Cổ cao vintage, phù hợp phối quần jean và áo khoác.',2290000,2690000,16,'Giày Nike'
UNION ALL SELECT 'Nike Pegasus 41 Black White','Nike','Giày chạy bộ nhẹ, đệm êm và thông thoáng.',2890000,3490000,10,'Giày Nike'
UNION ALL SELECT 'Nike Cortez White Red Blue','Nike','Mẫu retro kinh điển, form gọn và dễ phối đồ.',1990000,2390000,20,'Giày Nike'

UNION ALL SELECT 'Adidas Samba OG Cloud White','Adidas','Mẫu Samba hot trend, chất da mềm, kiểu dáng gọn gàng.',2590000,2990000,20,'Giày Adidas'
UNION ALL SELECT 'Adidas Campus 00s Grey','Adidas','Dáng chunky nhẹ, màu xám dễ phối với quần jean và quần ống rộng.',2390000,2790000,22,'Giày Adidas'
UNION ALL SELECT 'Adidas Superstar Trắng Sọc Đen','Adidas','Thiết kế mũi sò biểu tượng, bền và dễ mang.',1890000,2290000,30,'Giày Adidas'
UNION ALL SELECT 'Adidas Stan Smith Green','Adidas','Giày tennis cổ điển, tối giản, hợp phong cách sạch sẽ.',1790000,2190000,27,'Giày Adidas'
UNION ALL SELECT 'Adidas Forum Low White Blue','Adidas','Phối màu trắng xanh trẻ trung, quai dán cá tính.',2290000,2690000,16,'Giày Adidas'
UNION ALL SELECT 'Adidas Gazelle Indoor Blue','Adidas','Chất da lộn mềm, phối màu xanh nổi bật.',2490000,2890000,13,'Giày Adidas'
UNION ALL SELECT 'Adidas Ultraboost Light Black','Adidas','Đệm Boost êm, phù hợp chạy bộ và đi lại nhiều.',3290000,3890000,9,'Giày Adidas'
UNION ALL SELECT 'Adidas Spezial Brown Gum','Adidas','Phong cách terrace cổ điển, đế gum bền và đẹp.',2390000,2790000,14,'Giày Adidas'

UNION ALL SELECT 'New Balance 550 White Green','New Balance','Form retro bóng rổ, phối màu trắng xanh nổi bật.',2590000,3190000,14,'Giày New Balance'
UNION ALL SELECT 'New Balance 530 Silver Navy','New Balance','Đệm êm, phong cách dad shoes, phù hợp đi bộ nhiều.',2190000,2590000,26,'Giày New Balance'
UNION ALL SELECT 'New Balance 574 Classic Grey','New Balance','Mẫu cổ điển màu xám, nhẹ và bền, dễ phối đồ.',1890000,2290000,24,'Giày New Balance'
UNION ALL SELECT 'New Balance 327 Moonbeam','New Balance','Thiết kế retro, đế răng cưa cá tính, màu kem thanh lịch.',2290000,2790000,19,'Giày New Balance'
UNION ALL SELECT 'New Balance 2002R Protection Pack','New Balance','Dáng chunky cao cấp, phối màu bụi và cá tính.',3290000,3890000,8,'Giày New Balance'
UNION ALL SELECT 'New Balance 9060 Sea Salt','New Balance','Đệm dày êm chân, dáng hiện đại và nổi bật.',3490000,4090000,7,'Giày New Balance'
UNION ALL SELECT 'New Balance 1906R Metallic','New Balance','Phong cách running retro, thoáng khí và nhẹ chân.',2890000,3390000,11,'Giày New Balance'
UNION ALL SELECT 'New Balance 990v5 Grey','New Balance','Form cao cấp, phối màu xám kinh điển, rất êm khi đi lâu.',4290000,4990000,5,'Giày New Balance'

UNION ALL SELECT 'Puma Suede Classic Black White','Puma','Chất liệu da lộn mềm, kiểu dáng cổ điển không lỗi mốt.',1590000,1990000,32,'Giày Puma'
UNION ALL SELECT 'Puma Palermo Vintage Green','Puma','Phối màu xanh vintage, phù hợp phong cách casual.',1890000,2290000,21,'Giày Puma'
UNION ALL SELECT 'Puma RS-X Reinvention','Puma','Dáng thể thao chunky, đệm êm, nổi bật khi xuống phố.',2190000,2690000,13,'Giày Puma'
UNION ALL SELECT 'Puma Slipstream Lo White','Puma','Phong cách bóng rổ cổ điển, da mềm và chắc chân.',1990000,2390000,17,'Giày Puma'
UNION ALL SELECT 'Puma Carina Street Pink','Puma','Màu hồng nhẹ nhàng, phù hợp phối đồ nữ tính.',1490000,1890000,23,'Giày Puma'
UNION ALL SELECT 'Puma Future Rider Play On','Puma','Màu sắc trẻ trung, nhẹ và êm cho hoạt động hằng ngày.',1790000,2190000,18,'Giày Puma'

UNION ALL SELECT 'MLB Chunky Liner New York Yankees','MLB','Giày đế cao hack dáng, logo NY cá tính.',2290000,2790000,17,'Giày MLB'
UNION ALL SELECT 'MLB Big Ball Chunky Boston Red Sox','MLB','Thiết kế Hàn Quốc nổi bật, phù hợp phối đồ streetwear.',2390000,2890000,11,'Giày MLB'
UNION ALL SELECT 'MLB Playball Origin LA Dodgers','MLB','Dáng thấp nhẹ chân, màu trắng xanh dễ mang.',1590000,1890000,25,'Giày MLB'
UNION ALL SELECT 'MLB Chunky Classic Monogram','MLB','Họa tiết monogram nổi bật, đế cao và chắc chắn.',2690000,3190000,9,'Giày MLB'
UNION ALL SELECT 'MLB Big Ball Chunky A New York','MLB','Logo lớn cá tính, tạo điểm nhấn cho outfit.',2490000,2990000,12,'Giày MLB'
UNION ALL SELECT 'MLB Runner Basic Black','MLB','Màu đen dễ đi, form thể thao và nhẹ chân.',1890000,2290000,18,'Giày MLB'

UNION ALL SELECT 'Converse Chuck Taylor 1970s High Black','Converse','Giày vải cổ cao huyền thoại, dễ phối nhiều phong cách.',1690000,1990000,29,'Giày Converse'
UNION ALL SELECT 'Converse Chuck Taylor 1970s Low Parchment','Converse','Màu kem cổ thấp, đơn giản, phù hợp đi học và đi chơi.',1590000,1890000,34,'Giày Converse'
UNION ALL SELECT 'Converse Run Star Hike Black','Converse','Đế răng cưa cá tính, tăng chiều cao, nổi bật khi phối đồ.',2190000,2590000,10,'Giày Converse'
UNION ALL SELECT 'Converse One Star Pro Suede','Converse','Da lộn mềm, biểu tượng ngôi sao, phong cách skate.',1790000,2190000,23,'Giày Converse'
UNION ALL SELECT 'Converse Chuck 70 Plus Canvas','Converse','Thiết kế biến tấu hiện đại, cá tính và khác biệt.',2090000,2490000,12,'Giày Converse'
UNION ALL SELECT 'Converse Weapon CX White','Converse','Phong cách bóng rổ cổ điển, đệm CX êm hơn.',2290000,2690000,14,'Giày Converse'

UNION ALL SELECT 'Vans Old Skool Black White','Vans','Mẫu skate kinh điển, bền và cực dễ phối đồ.',1490000,1790000,31,'Giày Vans'
UNION ALL SELECT 'Vans Authentic Classic Black','Vans','Form thấp basic, phù hợp đi học và đi chơi.',1290000,1590000,28,'Giày Vans'
UNION ALL SELECT 'Vans Knu Skool Navy','Vans','Dáng phồng retro, dây giày bản to nổi bật.',1890000,2290000,16,'Giày Vans'
UNION ALL SELECT 'Vans Sk8-Hi Black White','Vans','Cổ cao cá tính, hợp phong cách streetwear.',1690000,1990000,19,'Giày Vans'
UNION ALL SELECT 'Vans Slip-On Checkerboard','Vans','Không dây tiện lợi, họa tiết caro biểu tượng.',1390000,1690000,22,'Giày Vans'
UNION ALL SELECT 'Vans Era 95 DX Anaheim','Vans','Chất vải đẹp, form cổ điển và thoải mái.',1590000,1890000,15,'Giày Vans'

UNION ALL SELECT 'Asics Gel-Kayano 14 Cream Black','Asics','Giày chạy bộ retro, đệm Gel êm và thoáng.',3290000,3890000,9,'Giày Asics'
UNION ALL SELECT 'Asics Gel-1130 White Clay','Asics','Tông trắng be nhẹ nhàng, dễ phối đồ hằng ngày.',2390000,2890000,13,'Giày Asics'
UNION ALL SELECT 'Asics Gel-NYC Graphite Grey','Asics','Form thể thao hiện đại, đế êm và chắc chân.',2990000,3490000,8,'Giày Asics'
UNION ALL SELECT 'Asics Japan S White Black','Asics','Thiết kế tối giản, giá tốt, hợp nhiều độ tuổi.',1490000,1790000,24,'Giày Asics'
UNION ALL SELECT 'Asics Gel-Lyte III Cream','Asics','Lưỡi gà tách đặc trưng, màu kem sang và dễ đi.',2490000,2990000,10,'Giày Asics'
UNION ALL SELECT 'Asics Novablast 4 Black','Asics','Đệm nảy tốt, phù hợp chạy bộ và tập luyện.',3190000,3690000,7,'Giày Asics'
) AS src
-- Thêm các cột cố định vào đây
CROSS JOIN (SELECT
    'https://images.unsplash.com/photo-1542291026-7eec264c27ff?q=80&w=900&auto=format&fit=crop' AS image_url,
    '36, 37, 38, 39, 40, 41, 42, 43' AS size_text,
    1 AS active
) AS defaults;
