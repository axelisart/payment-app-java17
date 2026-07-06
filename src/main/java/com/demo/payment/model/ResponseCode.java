package com.demo.payment.model;

// No changes from the Java 11 original.
// Enum is the correct abstraction for a closed set of named code+message pairs.
public enum ResponseCode {
    APPROVED("00", "Approved"),
    DECLINED("05", "Do not honor"),
    INSUFFICIENT_FUNDS("51", "Insufficient funds"),
    EXPIRED_CARD("54", "Expired card"),
    INVALID_CARD("14", "Invalid card number"),
    ALREADY_CAPTURED("77", "Transaction already captured"),
    ALREADY_REFUNDED("78", "Transaction already refunded"),
    NOT_AUTHORIZED("79", "Transaction not in authorized state"),
    NOT_FOUND("80", "Transaction not found");

    private final String code;
    private final String message;

    ResponseCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
}
