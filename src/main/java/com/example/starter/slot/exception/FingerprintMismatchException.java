package com.example.starter.slot.exception;

/**
 * 同键异参重放：requestId 已存在但申请指纹不一致，HTTP 409。
 */
public class FingerprintMismatchException extends RuntimeException {

    public FingerprintMismatchException(String requestId) {
        super("fingerprint mismatch for requestId: " + requestId);
    }
}
