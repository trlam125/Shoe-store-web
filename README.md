# LSHOE Store

LSHOE Store là website bán giày được xây dựng bằng Spring Boot, Thymeleaf và PostgreSQL. Dự án hỗ trợ mua hàng, quản lý đơn hàng, quản trị sản phẩm, chatbot tư vấn và một số tính năng AI.

## Chức năng chính

- Đăng ký, đăng nhập và xác minh email
- Xem, tìm kiếm và lọc sản phẩm
- Giỏ hàng và đặt hàng COD
- Theo dõi, quản lý và hủy đơn hàng
- Quản trị sản phẩm, biến thể, tồn kho và đơn hàng
- Chatbot tư vấn sản phẩm
- Tìm sản phẩm bằng hình ảnh và hỗ trợ phân tích dữ liệu

## Công nghệ sử dụng

- Java 21, Spring Boot 3.5
- Spring Security, Spring Data JPA, Thymeleaf
- PostgreSQL 16 và Flyway
- Python, FastAPI cho dịch vụ AI
- HTML, CSS và JavaScript

## Yêu cầu

- JDK 21
- Maven 3.9 trở lên
- PostgreSQL 16 hoặc Docker Desktop
- Python 3.11 nếu sử dụng dịch vụ AI

## Cài đặt và chạy

### 1. Tạo cơ sở dữ liệu

Có thể khởi động PostgreSQL bằng Docker:

```powershell
docker compose up -d postgres
```

### 2. Cấu hình ứng dụng

Sao chép file cấu hình mẫu:

```powershell
Copy-Item .env.example .env
```

Sau đó cập nhật thông tin kết nối PostgreSQL, tài khoản quản trị, email và các API key cần sử dụng trong file `.env`.

Khi triển khai production, đặt:

```env
SPRING_PROFILES_ACTIVE=prod
APP_PUBLIC_BASE_URL=https://ten-mien-cua-ban.example
```

### 3. Chạy website

```powershell
mvn spring-boot:run
```

Hoặc mở project bằng IntelliJ IDEA và chạy lớp `LshoeStoreApplication`.

Mặc định website chạy tại:

```text
http://localhost:8081
```

## Dịch vụ AI

Dịch vụ AI sử dụng chung cấu hình trong file `.env` ở thư mục gốc.

Chạy thủ công:

```powershell
cd ai-service
.\run.bat
```

Dịch vụ mặc định chạy tại `http://127.0.0.1:8001`. Có thể để Spring Boot tự khởi động dịch vụ bằng cấu hình `AI_SERVICE_AUTOSTART=true`.

## Truy cập qua Cloudflare Tunnel

Khởi động website trước, sau đó chạy:

```powershell
.\run-cloudflare.bat
```

Script hỗ trợ Quick Tunnel và Named Tunnel, không yêu cầu file PowerShell. Với Quick Tunnel, URL công khai được lưu tạm trong `.runtime/cloudflare-public-url.txt`.

## Lưu ý

- Không đưa file `.env`, mật khẩu hoặc API key lên Git.
- Nên sử dụng mật khẩu mạnh cho PostgreSQL và tài khoản quản trị.
- Database cũ cần được sao lưu trước khi thay đổi migration hoặc cấu trúc dữ liệu.
