package com.example.starter.carbon.web;

import com.example.starter.carbon.service.IdempotencyConflictException;
import com.example.starter.carbon.service.NotFoundException;
import com.example.starter.carbon.service.SurrenderRuleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 清缴服务统一错误响应：409 同键异参，422 业务规则拒绝，400 参数非法，404 凭证不存在。
 */
@RestControllerAdvice
public class SurrenderExceptionHandler {

    /** 凭证不存在（骨架阶段由服务抛出 IllegalArgumentException 等兜底为 404 时使用）。 */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(SurrenderRuleException.class)
    public ResponseEntity<Map<String, Object>> handleRule(SurrenderRuleException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", message));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of(
                "code", code,
                "message", message == null ? "" : message,
                "timestamp", Instant.now().toString());
    }
}
