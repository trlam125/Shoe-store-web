# LSHOE Store

Website bán giày sneaker xây dựng bằng Spring Boot + Thymeleaf + MySQL, tích hợp chatbot AI tư vấn sản phẩm.

---

## Công nghệ sử dụng

| Thành phần | Công nghệ |
|---|---|
| Backend | Spring Boot 3.5.3 / Java 21 |
| Template engine | Thymeleaf + thymeleaf-extras-springsecurity6 |
| Database | MySQL 8+ / Spring Data JPA |
| Bảo mật | Spring Security (BCrypt) |
| Chatbot API | Python / FastAPI / NVIDIA API |
| Build tool | Maven |

---

## Tính năng

### Khách hàng
- Xem danh sách sản phẩm, lọc theo danh mục, tìm kiếm theo tên
- Xem chi tiết sản phẩm
- Thêm / cập nhật / xóa sản phẩm trong giỏ hàng
- Giỏ hàng **lưu vào DB** — không mất khi đăng xuất
- Khi đăng nhập, giỏ hàng guest tự động merge vào tài khoản
- Đặt hàng COD với thông tin người nhận
- Xem lịch sử đơn hàng

### Tài khoản
- Đăng ký (validate email, mật khẩu tối thiểu 6 ký tự)
- Đăng nhập / đăng xuất

### Admin (`/admin`)
- Dashboard thống kê số sản phẩm và đơn hàng
- Thêm / sửa / ẩn sản phẩm (soft delete — không mất lịch sử đơn hàng)
- Xem và cập nhật trạng thái đơn hàng

### Chatbot AI
- Bubble chat góc phải dưới màn hình trên mọi trang
- Tư vấn giày, size, phong cách phối đồ bằng tiếng Việt
- Lưu lịch sử hội thoại trong phiên

---

## Cài đặt và chạy

### Yêu cầu
- Java 21+
- MySQL 8+
- Maven 3.9+
- Python 3.10+ (cho chatbot)

### 1. Clone project

```bash
git clone <repo-url>
cd lshoe-store
```

### 2. Cấu hình database

Mở `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/lshoe_store?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh
spring.datasource.username=root
spring.datasource.password=YOUR_PASSWORD
server.port=8081
```

> Database tự tạo khi chạy lần đầu nhờ `createDatabaseIfNotExist=true`.

### 3. Chạy Spring Boot

```bash
mvn spring-boot:run
```

Truy cập: [http://localhost:8081](http://localhost:8081)

### 4. Chạy Chatbot API (tùy chọn)

Cấu hình API key trong `Chatbot/.env`:

```env
NVIDIA_API_KEY=your_api_key_here
```

Khởi động server:

```bash
Chatbot\start_api.bat
```

hoặc thủ công:

```bash
cd Chatbot
venv\Scripts\activate
uvicorn api:app --host 0.0.0.0 --port 8000 --reload
```

> Chatbot chạy ở port 8000, Spring Boot tự proxy — không cần cấu hình thêm.

---

## Tài khoản mặc định

Tự động tạo khi khởi động lần đầu:

| Email | Mật khẩu | Vai trò |
|---|---|---|
| admin@lshoe.vn | admin123 | ADMIN |

---

## Cấu trúc project

```
lshoe-store/
├── Chatbot/
│   ├── api.py               # FastAPI server (POST /api/chat)
│   ├── app.py               # Streamlit app (standalone)
│   ├── start_api.bat        # Script khởi động API
│   ├── requirements.txt
│   └── .env                 # NVIDIA_API_KEY (không commit)
│
├── src/main/java/com/example/lshoestore/
│   ├── config/
│   │   ├── CartMergeLoginHandler.java   # Merge guest cart khi login
│   │   ├── DataSeeder.java              # Seed dữ liệu mẫu
│   │   └── SecurityConfig.java
│   ├── controller/
│   │   ├── AdminController.java
│   │   ├── AuthController.java
│   │   ├── CartController.java
│   │   ├── ChatbotController.java       # Proxy → FastAPI :8000
│   │   ├── HomeController.java
│   │   └── OrderController.java
│   ├── model/
│   │   ├── CartItem.java                # DTO (không persist)
│   │   ├── Category.java
│   │   ├── Order.java
│   │   ├── OrderItem.java
│   │   ├── OrderStatus.java             # Enum trạng thái đơn hàng
│   │   ├── Product.java
│   │   ├── SavedCartItem.java           # Giỏ hàng lưu DB
│   │   └── User.java
│   ├── repository/
│   ├── service/
│   │   ├── CartService.java             # DB cart (user) + session cart (guest)
│   │   └── CustomUserDetailsService.java
│   └── LshoeStoreApplication.java
│
├── src/main/resources/
│   ├── static/css/style.css
│   ├── templates/
│   │   ├── admin/
│   │   ├── auth/
│   │   ├── cart/
│   │   ├── order/
│   │   ├── product/
│   │   ├── fragments.html               # Header, footer, chat bubble
│   │   └── index.html
│   └── application.properties
│
├── seed_data.sql            # Insert dữ liệu thủ công qua MySQL
├── .gitignore
├── .gitattributes
└── pom.xml
```

---

## Phân quyền

| URL | Quyền truy cập |
|---|---|
| `/`, `/products/**` | Tất cả |
| `/login`, `/register` | Tất cả |
| `/cart/**`, `/checkout`, `/orders/**` | Đã đăng nhập |
| `/admin/**` | ROLE_ADMIN |
| `/chatbot/chat` | Tất cả |

---

## Trạng thái đơn hàng

```
CHO_XAC_NHAN → DANG_CHUAN_BI → DANG_GIAO → HOAN_THANH
                                           ↘ DA_HUY
```

Admin cập nhật thủ công tại `/admin/orders`.

---

## Kiến trúc Chatbot

```
Browser ──POST /chatbot/chat──▶ Spring Boot :8081
                                      │
                               (proxy forward)
                                      │
                               FastAPI :8000
                                      │
                               NVIDIA API (GPT)
```

Spring Boot đóng vai proxy để ẩn API key và tránh CORS.
