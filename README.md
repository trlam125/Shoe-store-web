# LSHOE Store

LSHOE Store là website bán giày sử dụng Spring Boot, Thymeleaf, PostgreSQL và một dịch vụ FastAPI cho các chức năng AI/ML.

Project được cấu hình để chạy theo hai chế độ mà không cần đổi source code:

- **Local/IntelliJ:** bấm Run `LshoeStoreApplication`; Spring Boot tự chuẩn bị `.venv` và khởi động FastAPI ở `127.0.0.1:8001`.
- **Vercel:** `Dockerfile.vercel` đóng gói Spring Boot + FastAPI vào cùng một container. Spring là HTTP server public, FastAPI chỉ chạy nội bộ trên loopback.

## Công nghệ

- Java 21, Spring Boot 3.5
- Spring Security, Spring Data JPA, Thymeleaf
- PostgreSQL 16, Flyway
- Python 3.10-3.13 cho local; container Vercel dùng Python do image cung cấp
- FastAPI, pandas, scikit-learn, PyTorch/torchvision
- HTML, CSS, JavaScript

## Chạy local bằng IntelliJ

### 1. Khởi động PostgreSQL

Nếu dùng Docker Desktop:

```powershell
docker compose up -d postgres
```

Database local mặc định:

```text
jdbc:postgresql://localhost:5432/lshoe_store
username: postgres
password: postgres
```

### 2. Tạo `.env`

```powershell
Copy-Item .env.example .env
```

Điền các thông tin thực tế như database, email và API key vào `.env`. Không commit `.env` lên Git.

### 3. Python local

Project chấp nhận Python 3.10, 3.11, 3.12 hoặc 3.13. Không bắt buộc cài riêng Python 3.11 nếu máy đã có một phiên bản hỗ trợ.

Khi `.venv` chưa tồn tại, lần đầu Spring Boot chạy sẽ gọi `setup-venv.bat` trên Windows, cài dependency trong `requirements.txt`, sau đó khởi động FastAPI. Các lần chạy sau tiếp tục dùng `.venv` đó.

Có thể chuẩn bị trước bằng:

```powershell
.\setup-venv.bat
```

### 4. Run trong IntelliJ

Mở:

```text
src/main/java/com/example/lshoestore/LshoeStoreApplication.java
```

sau đó bấm nút **Run** của IntelliJ.

Mặc định:

```text
Website: http://localhost:8081
FastAPI: http://127.0.0.1:8001
```

Profile mặc định là `dev`. Ở local, ảnh sản phẩm do admin upload vẫn được lưu vào `uploads/products` để thao tác nhanh như trước.

## Deploy nguyên repo lên Vercel

Project dùng **một Vercel container**, không cần tách Spring Boot và FastAPI thành hai repository/project.

```text
Browser
   |
   v
Spring Boot : $PORT        <- public
   |
   +---- http://127.0.0.1:8001 ----> FastAPI/PyTorch
   |
   +----> PostgreSQL cloud
```

`Dockerfile.vercel` được Vercel tự nhận diện. Container production:

- build Spring Boot bằng Java 21;
- cài Python và dependency AI;
- dùng PyTorch CPU-only để giảm kích thước image;
- tải sẵn ResNet18 weights trong lúc build;
- chạy Spring Boot trên `$PORT`;
- chạy FastAPI nội bộ ở `127.0.0.1:8001`;
- tắt cơ chế Spring tự tạo `.venv` ở production.

### 1. Database cloud

Vercel container là stateless nên PostgreSQL không chạy từ `docker-compose.yml` trên production. Dùng một PostgreSQL managed như Neon/Supabase/Railway hoặc dịch vụ PostgreSQL khác.

Project chấp nhận hai dạng `DATABASE_URL` khi chạy bằng `Dockerfile.vercel`:

```text
postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require
```

hoặc:

```text
jdbc:postgresql://HOST/DATABASE?sslmode=require
```

Nếu URL dạng `postgresql://` có username/password bên trong, `vercel-entrypoint.sh` sẽ tự chuyển sang JDBC cho Spring và chia sẻ credentials với FastAPI.

Nếu dùng JDBC URL không chứa credentials, cấu hình thêm:

```text
DB_USERNAME=...
DB_PASSWORD=...
```

### 2. Environment Variables tối thiểu trên Vercel

```text
DATABASE_URL=postgresql://...
NVIDIA_API_KEY=...
```

Nếu chatbot không sử dụng NVIDIA thì `NVIDIA_API_KEY` có thể để trống, nhưng chức năng chatbot tương ứng sẽ không hoạt động.

Các biến email nếu dùng đăng ký/xác minh/reset mật khẩu:

```text
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=...
MAIL_PASSWORD=...
MAIL_SMTP_AUTH=true
MAIL_STARTTLS=true
```

Các biến tùy chọn:

```text
BOOTSTRAP_ADMIN_EMAIL=...
BOOTSTRAP_ADMIN_PASSWORD=...
SEED_DEMO_DATA=false
AI_INTERNAL_API_KEY=...
```

`SPRING_PROFILES_ACTIVE=prod`, `AI_SERVICE_AUTOSTART=false` và `PRODUCT_IMAGE_STORAGE=database` đã có mặc định trong container, nên không bắt buộc nhập lại trên Vercel.

Nếu Vercel System Environment Variables được bật, app tự dùng `VERCEL_PROJECT_PRODUCTION_URL` làm public base URL cho link xác minh/reset. Có thể ghi đè bằng:

```text
APP_PUBLIC_BASE_URL=https://domain-cua-ban.com
```

### 3. Upload ảnh trên Vercel

Local vẫn dùng filesystem. Production mặc định dùng PostgreSQL thông qua bảng `product_image_asset`, được tạo bởi Flyway migration `V5__add_persistent_product_images.sql`.

Nhờ vậy ảnh admin upload không bị mất khi Vercel scale-to-zero hoặc redeploy container.

Do giới hạn request body của Vercel, profile production mặc định giới hạn upload khoảng 4 MB. Local vẫn giữ giới hạn cao hơn theo `.env`.

### 4. Deploy

Push repository lên GitHub rồi Import repository đó vào Vercel. Giữ Root Directory là thư mục gốc repository. Vercel sẽ phát hiện `Dockerfile.vercel`.

Sau khi thêm Environment Variables, deploy lại project. Flyway sẽ tự chạy migration khi Spring Boot khởi động.

## Các file deployment quan trọng

```text
Dockerfile.vercel
vercel-entrypoint.sh
.dockerignore
src/main/resources/application-prod.properties
src/main/resources/db/migration/V5__add_persistent_product_images.sql
```

Các file local vẫn được giữ nguyên:

```text
docker-compose.yml
setup-venv.bat
setup-venv.sh
ai-service/run.bat
ai-service/run.sh
.env
```

Vì vậy deploy Vercel không làm mất workflow bấm Run trực tiếp từ IntelliJ.

## Lưu ý bảo mật

- Không commit `.env`, mật khẩu, API key hoặc mail app password.
- Production nên dùng password database mạnh và SSL.
- `AI_INTERNAL_API_KEY` không bắt buộc với mô hình một container vì Spring gọi FastAPI qua loopback; có thể đặt thêm một secret nếu muốn defense-in-depth.
- Sao lưu database trước khi triển khai lên database đang có dữ liệu thật.
