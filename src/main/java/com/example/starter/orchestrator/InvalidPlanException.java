package com.example.starter.orchestrator;

/**
 * 请求参数非法（计划结构错误，如引用了不存在的节点），映射 HTTP 400。
 */
public class InvalidPlanException extends RuntimeException {
    public InvalidPlanException(String message) {
        super(message);
    }
}
