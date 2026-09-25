package com.example.starter.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.domain.BusinessException;

/**
 * 统一异常响应：业务异常按其映射状态返回，参数校验返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务规则异常。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getMessage()));
    }

    /** Bean Validation 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        return ResponseEntity.badRequest().body(body(message));
    }

    private Map<String, Object> body(String message) {
        return Map.of("timestamp", Instant.now().toString(), "message", message);
    }
}
