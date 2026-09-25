package com.example.starter.slot.exception;

/**
 * 请求期终态冲突：航季已冻结（HTTP 409）或系列已退回后重放等。
 */
public class SlotStateException extends RuntimeException {

    public SlotStateException(String message) {
        super(message);
    }
}
