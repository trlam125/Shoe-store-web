# LSHOE Store

Website bán giày sneaker xây dựng bằng Spring Boot, Thymeleaf và MySQL.

---

## Công nghệ sử dụng

| Thành phần | Công nghệ |
|---|---|
| Backend | Spring Boot 3.5.3 |
| Ngôn ngữ | Java 21 |
| Template engine | Thymeleaf + thymeleaf-extras-springsecurity6 |
| Database | MySQL 8+ |
| ORM | Spring Data JPA / Hibernate |
| Bảo mật | Spring Security (BCrypt) |
| Validation | Spring Boot Starter Validation |
| Build tool | Maven |

---

## Tính năng

### Khách hàng
- Xem danh sách sản phẩm, lọc theo danh mục, tìm kiếm theo tên
- Xem chi tiết sản phẩm
- Thêm / cập nhật / xóa sản phẩm trong giỏ hàng (session-based)
- Đặt hàng COD với thông tin người nhận
- Xem lịch sử đơn hàng

### Tài khoản
- Đăng ký (validate email, mật khẩu tối thiểu 6 ký tự)
- Đăng nhập / đăng xuất (Spring Security form login)

### Admin (`/admin`)
- Dashboard thống kê số sản phẩm và đơn hàng
- Thêm / sửa / ẩn sản phẩm (soft delete)
- Quản lý danh mục
- Xem và cập nhật trạng thái đơn hàng

---

## Cài đặt và chạy

### Yêu cầu
- Java 21+
- MySQL 8+
- Maven 3.9+

### 1. Clone project

```bash
git clone <repo-url>
cd lshoe-store
```

### 2. Tạo database

```sql
CREATE DATABASE lshoe_store CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

> Hoặc để Spring tự tạo — `application.properties` đã có `createDatabaseIfNotExist=true`.

### 3. Cấu hình kết nối

Mở `src/main/resources/application.properties` và chỉnh lại thông tin:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/lshoe_store?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh
spring.datasource.username=root
spring.datasource.password=YOUR_PASSWORD
server.port=8081
```

### 4. Chạy ứng dụng

```bash
mvn spring-boot:run
```

Truy cập tại: [http://localhost:8081](http://localhost:8081)

---

## Tài khoản mặc định

Khi khởi động lần đầu, hệ thống tự tạo tài khoản admin:

| Email | Mật khẩu | Vai trò |
|---|---|---|
| admin@lshoe.vn | admin123 | ADMIN |

---

## Cấu trúc project

```
src/main/java/com/example/lshoestore/
├── config/
│   ├── DataSeeder.java          # Seed dữ liệu mẫu khi khởi động
│   └── SecurityConfig.java      # Cấu hình Spring Security
├── controller/
│   ├── AdminController.java     # Quản lý admin (/admin/**)
│   ├── AuthController.java      # Đăng ký / đăng nhập
│   ├── CartController.java      # Giỏ hàng (/cart/**)
│   ├── HomeController.java      # Trang chủ và danh sách sản phẩm
│   └── OrderController.java     # Checkout và lịch sử đơn hàng
├── model/
│   ├── CartItem.java            # Item trong giỏ hàng (session, không persist)
│   ├── Category.java
│   ├── Order.java
│   ├── OrderItem.java
│   ├── OrderStatus.java         # Enum: CHO_XAC_NHAN, DANG_CHUAN_BI, DANG_GIAO, HOAN_THANH, DA_HUY
│   ├── Product.java
│   └── User.java
├── repository/                  # Spring Data JPA repositories
├── service/
│   ├── CartService.java         # Giỏ hàng lưu trong session (@SessionScope)
│   └── CustomUserDetailsService.java
└── LshoeStoreApplication.java

src/main/resources/
├── static/css/style.css
├── templates/
│   ├── admin/                   # dashboard, orders, product-form, products
│   ├── auth/                    # login, register
│   ├── cart/                    # view
│   ├── order/                   # checkout, list
│   ├── product/                 # detail, list
│   ├── fragments.html           # header/footer dùng chung
│   └── index.html
└── application.properties
```

---

## Phân quyền

| URL | Quyền truy cập |
|---|---|
| `/`, `/products/**` | Tất cả |
| `/login`, `/register` | Tất cả |
| `/cart/**`, `/checkout`, `/orders` | Đã đăng nhập |
| `/admin/**` | ROLE_ADMIN |

---

## Trạng thái đơn hàng

```
CHO_XAC_NHAN → DANG_CHUAN_BI → DANG_GIAO → HOAN_THANH
                                           ↘ DA_HUY
```

Admin cập nhật thủ công tại `/admin/orders`.
