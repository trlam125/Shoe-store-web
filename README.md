# LSHOE Store

LSHOE là website bán giày xây dựng bằng Spring Boot, Thymeleaf và PostgreSQL. Dự án có giỏ hàng cho khách và tài khoản, đặt hàng COD, quản trị sản phẩm/đơn hàng, chatbot tư vấn và dịch vụ AI tìm giày bằng ảnh, phân nhóm khách hàng, dự báo tồn kho.

## Công nghệ

- Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA
- Thymeleaf, HTML/CSS/JavaScript thuần
- PostgreSQL 16
- Python 3.11 cho dịch vụ AI FastAPI
- ResNet18, K-Means và Random Forest

## Các bản vá logic và bảo mật

- Bỏ tài khoản admin viết cứng. Admin đầu tiên chỉ được tạo từ biến môi trường khi bảng người dùng đang trống.
- Tự vô hiệu mật khẩu cũ `admin123` nếu database còn tài khoản mặc định từ phiên bản trước.
- Checkout dùng mã dùng một lần, khóa người dùng, giỏ và sản phẩm trong transaction để ngăn tạo đơn trùng và bán vượt kho.
- Admin sửa sản phẩm bằng DTO và optimistic locking, tránh ghi đè tồn kho cũ khi khách vừa đặt hàng.
- Giỏ hàng luôn tải lại giá, trạng thái và tồn kho hiện tại; sản phẩm ngừng bán hoặc hết hàng được loại khỏi giỏ.
- Các thao tác thêm/cập nhật giỏ được khóa theo người dùng để giảm lỗi request đồng thời.
- Token đặt lại mật khẩu được khóa và dùng một lần; đổi mật khẩu thu hồi toàn bộ phiên cũ.
- Phiên cũ từ phiên bản trước cũng bị buộc đăng nhập lại.
- Link đặt lại mật khẩu không được ghi log trong production và không dùng Host header để tạo link khi chưa cấu hình URL công khai.
- Bổ sung validation độ dài, định dạng email/số điện thoại, tồn kho, giá và dữ liệu checkout.
- ID không tồn tại trả trang 404 thay vì lỗi 500.
- Chatbot và tìm kiếm ảnh có giới hạn request; upload ảnh giới hạn loại file và dung lượng.
- FastAPI mặc định chỉ bind `127.0.0.1`; các API nội bộ yêu cầu secret hoặc chỉ chấp nhận loopback.
- Chống SSRF khi dịch vụ AI tải ảnh sản phẩm từ URL.
- Dự báo tồn kho bao gồm cả sản phẩm chưa từng bán; cache ảnh được vô hiệu theo phiên bản sản phẩm.
- Tự bổ sung `completed_at` cho đơn hoàn thành cũ.
- Cấu hình PostgreSQL mặc định thống nhất giữa Java, AI và Docker.
- Khóa NVIDIA thật đã được loại khỏi mã nguồn. Hãy thu hồi khóa cũ nếu ZIP trước từng được chia sẻ.

Chi tiết nằm trong [FIXES.md](FIXES.md).

## Cài đặt nhanh

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
```

Không dùng mật khẩu Gmail chính. Hãy tạo App Password. Khi chạy qua Cloudflare Quick Tunnel, cập nhật `APP_PUBLIC_BASE_URL` theo URL đang sử dụng trước khi gửi link đặt lại mật khẩu.

Nếu SMTP hoặc URL công khai chưa được cấu hình ở production, hệ thống vẫn trả thông báo chung nhưng không tạo/ghi lộ link reset.

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

## Chatbot NVIDIA

Thêm vào `.env`:

```env
NVIDIA_API_KEY=khóa-của-bạn
CHATBOT_MODEL=openai/gpt-oss-120b
```

Thư mục `Chatbot` chỉ là bản thử nghiệm độc lập; website chính gọi NVIDIA trực tiếp từ Spring Boot. Không commit `.env` hoặc API key.

## Cloudflare Tunnel

Chạy website trước, sau đó đặt `cloudflared.exe` cạnh `run-cloudflare.bat` và chạy:

```powershell
.\run-cloudflare.bat
```

Script cũng nhận `cloudflared` từ `PATH`. Với Named Tunnel:

```powershell
$env:TUNNEL_TOKEN="token-cua-ban"
.\run-cloudflare.bat
```

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
