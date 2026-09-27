package com.example.starter.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常到 HTTP 状态码与错误体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ApiConflictException ex) {
        return build(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(ApiBadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(ApiBadRequestException ex) {
        return build(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(ApiNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ApiNotFoundException ex) {
        return build(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(ApiUnprocessableException.class)
    public ResponseEntity<Map<String, Object>> handleUnprocessable(ApiUnprocessableException ex) {
        return build(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", message == null ? status.getReasonPhrase() : message));
    }
}
