package com.example.lshoestore.exception;

public class BusinessException extends RuntimeException {
    private final String code;

    public BusinessException(String message) { this(message, "business_error"); }
    public BusinessException(String message, String code) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}
