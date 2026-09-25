package com.example.starter.food.service;

/**
 * 业务规则违例（HTTP 400 / 409 等由全局处理器按具体子类映射）。
 */
public class BusinessException extends RuntimeException {

    private final int status;

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
