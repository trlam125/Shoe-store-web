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

    public boolean isTerminal() {
        return this == HOAN_THANH || this == DA_HUY;
    }

    /**
     * Chỉ cho phép đi tuần tự: chờ xác nhận -> chuẩn bị -> giao -> hoàn thành.
     * Đơn chỉ có thể hủy trước khi chuyển sang trạng thái đang giao.
     */
    public boolean canTransitionTo(OrderStatus next) {
        if (next == null || next == this) return false;

        return switch (this) {
            case CHO_XAC_NHAN -> next == DANG_CHUAN_BI || next == DA_HUY;
            case DANG_CHUAN_BI -> next == DANG_GIAO || next == DA_HUY;
            case DANG_GIAO -> next == HOAN_THANH;
            case HOAN_THANH, DA_HUY -> false;
        };
    }
}
