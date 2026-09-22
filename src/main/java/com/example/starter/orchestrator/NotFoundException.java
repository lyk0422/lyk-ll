package com.example.starter.orchestrator;

/**
 * 计划或节点不存在，映射 HTTP 404。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
