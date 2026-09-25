package com.example.starter.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一错误响应：{"error":"...","message":"..."}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "error", HttpStatus.valueOf(ex.getStatus()).getReasonPhrase(),
                "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(Map.of("error", "Bad Request", "message", message));
    }

    /** 跨字段参数校验（如非法阶段名）统一作为 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Bad Request", "message", ex.getMessage()));
    }

    /** 数据库唯一约束兜底（并发唯一受理）统一作为 409。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateKey(
            org.springframework.dao.DuplicateKeyException ex) {
        return ResponseEntity.status(409)
                .body(Map.of("error", "Conflict", "message", "并发冲突：该操作或该潜水组占用已被唯一受理"));
    }
}
