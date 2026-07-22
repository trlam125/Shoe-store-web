package com.example.lshoestore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CustomerUpdateForm {
    @NotBlank(message = "Họ tên không được để trống.")
    @Size(max = 120, message = "Họ tên không được vượt quá 120 ký tự.")
    private String fullName;

    @Pattern(regexp = "^$|^(0|\\+84)[0-9]{8,10}$", message = "Số điện thoại không hợp lệ.")
    @Size(max = 20, message = "Số điện thoại không được vượt quá 20 ký tự.")
    private String phone;

    @Size(max = 500, message = "Địa chỉ không được vượt quá 500 ký tự.")
    private String address;

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
}
