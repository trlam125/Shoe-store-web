# LSHOE Store

LSHOE là website bán giày xây dựng bằng Spring Boot, Thymeleaf và PostgreSQL. Dự án có giỏ hàng cho khách và tài khoản, đặt hàng COD, quản trị sản phẩm/đơn hàng, chatbot tư vấn và dịch vụ AI tìm giày bằng ảnh, phân nhóm khách hàng, dự báo tồn kho.

## Công nghệ

- Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA
- Thymeleaf, HTML/CSS/JavaScript thuần
- PostgreSQL 16
- Python 3.11 cho dịch vụ AI FastAPI
- ResNet18, K-Means và Random Forest## Cài đặt nhanh

### 1. Yêu cầu

- JDK 21
- Maven 3.9 trở lên hoặc IntelliJ IDEA có Maven tích hợp
- PostgreSQL 16, hoặc Docker Desktop
- Python 3.11 nếu dùng tính năng AI

### 2. Tạo database

Dùng Docker:

```powershell
docker compose up -d postgres
```

Hoặc tạo thủ công:

```sql
CREATE DATABASE lshoe_store;
```

### 3. Cấu hình ứng dụng

Sao chép file mẫu:

```powershell
Copy-Item .env.example .env
```

Tối thiểu cần chỉnh:

```env
DATABASE_URL=jdbc:postgresql://localhost:5432/lshoe_store
DB_USERNAME=postgres
DB_PASSWORD=postgres

APP_ENV=production
APP_PUBLIC_BASE_URL=http://localhost:8081
BOOTSTRAP_ADMIN_EMAIL=admin@example.com
BOOTSTRAP_ADMIN_PASSWORD=mat-khau-manh-cua-ban
SEED_DEMO_DATA=false
```

`BOOTSTRAP_ADMIN_EMAIL` và `BOOTSTRAP_ADMIN_PASSWORD` chỉ tạo admin khi bảng `users` đang hoàn toàn trống. Không có tài khoản mặc định trong mã nguồn.

Đặt `SEED_DEMO_DATA=true` nếu muốn thêm dữ liệu sản phẩm mẫu. Các danh mục nền vẫn được tạo khi database chưa có danh mục để trang thêm sản phẩm có thể sử dụng.

### 4. Chạy Spring Boot

```powershell
mvn spring-boot:run
```

Hoặc mở project bằng IntelliJ và chạy `LshoeStoreApplication`.

Truy cập:

```text
http://localhost:8081
```

## Cấu hình email quên mật khẩu

Ví dụ Gmail:

```env
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=email-cua-ban@gmail.com
MAIL_PASSWORD=mat-khau-ung-dung-16-ky-tu
MAIL_SMTP_AUTH=true
MAIL_STARTTLS=true
APP_PUBLIC_BASE_URL=https://ten-mien-cua-ban.example

## Dịch vụ AI

### 1. Cấu hình

```powershell
Copy-Item ai-service\.env.example ai-service\.env
```

Giá trị `AI_INTERNAL_API_KEY` trong `ai-service/.env` phải giống giá trị trong `.env` của Spring Boot.

### 2. Chạy thủ công

```powershell
cd ai-service
.\run.bat
```

Dịch vụ chạy tại `http://127.0.0.1:8001`. Spring Boot cũng có thể tự khởi động dịch vụ nếu:

```env
AI_SERVICE_AUTOSTART=true
```

Lần đầu chạy ResNet18 có thể cần tải trọng số mô hình và mất nhiều thời gian.

## Cấu trúc chính

```text
src/main/java/com/example/lshoestore/
├── config/       # Security, seed, migration và AI autostart
├── controller/   # Web/API controllers
├── dto/          # Form DTO, chống overposting
├── exception/    # Exception nghiệp vụ và 404
├── model/        # JPA entities
├── repository/   # Repository và truy vấn khóa
├── security/     # Principal và kiểm tra phiên
└── service/      # Giỏ hàng, đơn hàng, reset mật khẩu, AI

src/main/resources/
├── static/css/style.css
├── templates/
└── application.properties

ai-service/
├── app/main.py
├── run.py
├── run.bat
└── .env.example
```

## Lưu ý triển khai

- Không đưa `.env`, API key, App Password hoặc file log lên GitHub.
- Sao lưu PostgreSQL trước khi chạy bản mới trên database đang sử dụng.
- Hibernate sẽ tự bổ sung các cột/index mới bằng `ddl-auto=update`; production lâu dài nên chuyển sang Flyway hoặc Liquibase.
- Rate limit hiện lưu trong bộ nhớ từng tiến trình. Khi chạy nhiều server, nên chuyển sang Redis.
- Để tránh lỗi đồng thời giữa nhiều instance, nên bổ sung transaction isolation/project-level locking ở tầng database khi hệ thống mở rộng.
