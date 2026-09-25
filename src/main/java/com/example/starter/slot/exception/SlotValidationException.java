package com.example.starter.slot.exception;

/**
 * 请求参数不合法（时刻粒度、周列表等），HTTP 400。
 */
public class SlotValidationException extends RuntimeException {

    public SlotValidationException(String message) {
        super(message);
    }
}
