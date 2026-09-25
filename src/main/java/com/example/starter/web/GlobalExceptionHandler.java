package com.example.starter.web;

import com.example.starter.service.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 全局异常处理：业务异常按状态码返回 {code, message}；参数解析失败返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("code", e.code(), "message", e.getMessage(), "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "BAD_REQUEST", "message", "请求参数非法: " + e.getMessage(),
                        "timestamp", Instant.now().toString()));
    }
}
