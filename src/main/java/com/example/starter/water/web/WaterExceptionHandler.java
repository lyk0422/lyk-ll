package com.example.starter.water.web;

import java.time.Instant;
import java.util.Map;

import com.example.starter.water.support.BusinessRejectedException;
import com.example.starter.water.support.IdempotencyConflictException;
import com.example.starter.water.support.NotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务异常到 HTTP 状态码的统一映射。
 */
@RestControllerAdvice
public class WaterExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(BusinessRejectedException.class)
    public ResponseEntity<Map<String, Object>> rejected(BusinessRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body("BUSINESS_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("INVALID_REQUEST", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("INVALID_REQUEST", ex.getMessage()));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of("code", code, "message", message, "timestamp", Instant.now().toString());
    }
}
