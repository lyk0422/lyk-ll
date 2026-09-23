package com.example.starter.trajectory.exception;

/**
 * 请求载荷语义非法（如道路多边形不足三个顶点），对应 HTTP 400。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
