package com.example.starter.crane;

/**
 * 资源不存在或状态不允许当前操作（404/422 语义）。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
