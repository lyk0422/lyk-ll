package com.example.starter.slam.web;

/**
 * 请求语义不合法（如边引用不存在的关键帧、协方差格式错误），映射 HTTP 422。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
