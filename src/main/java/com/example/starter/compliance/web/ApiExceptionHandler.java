package com.example.starter.compliance.web;

import com.example.starter.compliance.ApiException;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常响应：{"code":..., "message":..., "timestamp":...}。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException ex) {
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateKeyException ex) {
        return respond(HttpStatus.CONFLICT, "UNIQUE_CONSTRAINT_CONFLICT", "唯一约束冲突");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "请求体格式错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
    }

    private static ResponseEntity<Map<String, Object>> respond(HttpStatus status, String code,
            String message) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message == null ? "" : message,
                "timestamp", Instant.now().toString()));
    }
}
