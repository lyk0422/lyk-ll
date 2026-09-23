package com.example.starter.spectrum.api;

import com.example.starter.spectrum.LeaseConflictException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 租约 API 全局异常处理。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LeaseConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(LeaseConflictException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "LEASE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "LEASE_ID_MISMATCH" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        Map<String, Object> body = body(ex.getCode(), ex.getMessage());
        if (!ex.getDetails().isEmpty()) {
            body.put("details", ex.getDetails());
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(body("MALFORMED_REQUEST", "请求体格式不正确或字段类型不匹配"));
    }

    private static Map<String, Object> body(String code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("code", code);
        map.put("message", message);
        return map;
    }
}
