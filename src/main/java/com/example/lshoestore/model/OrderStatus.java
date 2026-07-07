package com.example.lshoestore.model;

public enum OrderStatus {
    CHO_XAC_NHAN("Chờ xác nhận"),
    DANG_CHUAN_BI("Đang chuẩn bị"),
    DANG_GIAO("Đang giao"),
    HOAN_THANH("Hoàn thành"),
    DA_HUY("Đã hủy");

    private final String displayName;

    OrderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * State machine: kiểm tra xem có được phép chuyển sang trạng thái mới không.
     * HOAN_THANH và DA_HUY là trạng thái cuối — không cho phép chuyển tiếp.
     */
    public boolean canTransitionTo(OrderStatus next) {
        if (this == HOAN_THANH || this == DA_HUY) return false;
        return true;
    }
}
