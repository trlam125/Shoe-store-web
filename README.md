# LSHOE Store - Website bán giày Spring Boot + MySQL

Project demo website bán giày dùng **Java 21**, **Spring Boot 3.5.3**, **Maven**, **MySQL**, **Spring Security**, **Thymeleaf**, HTML và CSS.

Giao diện và dữ liệu đã được Việt hóa, tên cửa hàng là **LSHOE**. Project tự tạo bảng và dữ liệu mẫu khi chạy lần đầu.

---

## 1. Yêu cầu cài đặt

Bạn cần cài các phần mềm sau:

- JDK 21 trở lên. Nếu máy đang dùng Temurin 25.0.3 vẫn chạy được vì project đặt source là Java 21.
- IntelliJ IDEA.
- MySQL Server 8.x.
- Maven. Nếu IntelliJ đã có Maven tích hợp thì có thể chạy trực tiếp trong IntelliJ.

Kiểm tra Java trong PowerShell hoặc CMD:

```bash
java -version
javac -version
```

Nếu lệnh `java` không nhận, hãy kiểm tra biến môi trường `JAVA_HOME` và `Path`.

---

## 2. Tạo database MySQL

Mở MySQL Workbench hoặc terminal MySQL rồi chạy:

```sql
CREATE DATABASE lshoe_store
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

Nếu database đã tồn tại thì không cần tạo lại.

---

## 3. Cấu hình tài khoản MySQL

Mở file:

```text
src/main/resources/application.properties
```

Mặc định project đang để:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/lshoe_store?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Ho_Chi_Minh
spring.datasource.username=root
spring.datasource.password=123456
```

Nếu mật khẩu MySQL của bạn khác `123456`, hãy sửa dòng:

```properties
spring.datasource.password=mat_khau_mysql_cua_ban
```

Ví dụ mật khẩu là `abc@123`:

```properties
spring.datasource.password=abc@123
```

Nếu username MySQL không phải `root`, sửa thêm:

```properties
spring.datasource.username=ten_user_mysql_cua_ban
```

---

## 4. Mở project bằng IntelliJ

Không tạo New Project.

Làm như sau:

```text
IntelliJ IDEA -> File -> Open -> chọn thư mục lshoe-store
```

Sau khi mở project, chờ IntelliJ tải Maven dependencies.

Kiểm tra JDK:

```text
File -> Project Structure -> Project SDK -> chọn JDK 21 hoặc JDK 25
```

Nếu đang dùng Temurin 25.0.3 thì vẫn dùng được.

---

## 5. Chạy project

### Cách 1: Chạy bằng IntelliJ

Mở file:

```text
src/main/java/com/example/lshoestore/LshoeStoreApplication.java
```

Nhấn nút Run màu xanh ở hàm `main`.

### Cách 2: Chạy bằng Maven terminal

Tại thư mục gốc của project, chạy:

```bash
mvn spring-boot:run
```

Nếu muốn build file jar:

```bash
mvn clean package
```

Sau đó chạy:

```bash
java -jar target/lshoe-store-1.0.0.jar
```

---

## 6. Truy cập website

Sau khi chạy thành công, mở trình duyệt:

```text
http://localhost:8080
```

Các trang chính:

```text
/                 Trang chủ
/products         Danh sách sản phẩm
/cart             Giỏ hàng
/checkout         Thanh toán
/orders           Lịch sử đơn hàng
/login            Đăng nhập
/register         Đăng ký
/admin            Trang quản trị
/admin/products   Quản lý sản phẩm
/admin/orders     Quản lý đơn hàng
```

---

## 7. Tài khoản admin mặc định

Project tự tạo tài khoản admin khi chạy lần đầu:

```text
Email: admin@lshoe.vn
Mật khẩu: admin123
```

Sau khi đăng nhập admin, vào:

```text
http://localhost:8080/admin
```

---

## 8. Dữ liệu mẫu

Khi bảng `product` đang trống, project sẽ tự thêm dữ liệu mẫu gồm nhiều sản phẩm giày thuộc các nhóm:

- Nike
- Adidas
- New Balance
- Puma
- MLB
- Converse
- Vans
- Asics

File tạo dữ liệu mẫu nằm ở:

```text
src/main/java/com/example/lshoestore/config/DataSeeder.java
```

Nếu muốn tạo lại dữ liệu mẫu từ đầu, bạn có thể xóa database rồi tạo lại:

```sql
DROP DATABASE lshoe_store;
CREATE DATABASE lshoe_store
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

Sau đó chạy lại project.

---

## 9. Một số lỗi thường gặp

### Lỗi sai mật khẩu MySQL

Nếu log có dạng:

```text
Access denied for user 'root'@'localhost'
```

Hãy sửa lại:

```properties
spring.datasource.username=root
spring.datasource.password=mat_khau_dung_cua_ban
```

### Lỗi cổng 8080 đã được sử dụng

Nếu log báo port 8080 busy, sửa trong `application.properties`:

```properties
server.port=8081
```

Sau đó truy cập:

```text
http://localhost:8081
```

### Lỗi không tìm thấy Java

Nếu PowerShell báo:

```text
java is not recognized
```

Cần cài JDK cho toàn máy hoặc thêm đường dẫn JDK vào biến môi trường `Path`.

### Lỗi Maven chưa tải dependency

Trong IntelliJ, mở tab Maven và bấm Reload All Maven Projects.

Hoặc chạy:

```bash
mvn clean package
```

---

## 10. Công nghệ sử dụng

- Java 21
- Spring Boot 3.5.3
- Spring MVC
- Spring Data JPA
- Spring Security
- Thymeleaf
- MySQL 8
- HTML/CSS
- Maven

---

## 11. Ghi chú

Project phục vụ học tập và làm đồ án. Hình ảnh sản phẩm đang dùng link ảnh mẫu từ Unsplash. Khi làm bản hoàn thiện, bạn có thể thay bằng ảnh sản phẩm thật trong trang admin hoặc sửa trực tiếp trong dữ liệu mẫu.
