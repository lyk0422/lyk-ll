package com.example.starter.slot.exception;

/**
 * 目标资源不存在（如退回未知 requestId），HTTP 404。
 */
public class SlotNotFoundException extends RuntimeException {

    public SlotNotFoundException(String message) {
        super(message);
    }
}
