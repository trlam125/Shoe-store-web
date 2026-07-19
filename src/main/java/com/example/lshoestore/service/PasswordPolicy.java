package com.example.lshoestore.service;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    public static final int MIN_CHARACTERS = 8;
    public static final int MAX_BCRYPT_BYTES = 72;
    public static final String VALIDATION_MESSAGE =
            "Mật khẩu phải có ít nhất 8 ký tự và không vượt quá 72 byte UTF-8.";

    private PasswordPolicy() {
    }

    public static boolean isValidForBcrypt(String password) {
        return password != null
                && password.length() >= MIN_CHARACTERS
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BCRYPT_BYTES;
    }
}
