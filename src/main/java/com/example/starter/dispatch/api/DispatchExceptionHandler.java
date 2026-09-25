package com.example.starter.dispatch.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：{ code, message, at }。
 */
@RestControllerAdvice
public class DispatchExceptionHandler {

    @ExceptionHandler(DispatchException.class)
    public ResponseEntity<Map<String, Object>> handle(DispatchException ex) {
        return ResponseEntity.status(ex.status()).body(body(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .findFirst()
                .orElse("invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("VALIDATION_FAILED", message));
    }

    private Map<String, Object> body(String code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        map.put("at", Instant.now().toString());
        return map;
    }
}
