package com.example.starter.exception;

/**
 * 消息因容量等原因被拒绝时抛出。
 */
public class RejectedException extends RuntimeException {

    public RejectedException(String message) {
        super(message);
    }
}
