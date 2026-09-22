package com.example.starter.orchestrator;

/**
 * 计划存在显式或条件引用导致的循环依赖，映射 HTTP 422。
 * 消息中携带完整依赖路径。
 */
public class CyclicDependencyException extends RuntimeException {
    public CyclicDependencyException(String message) {
        super(message);
    }
}
