package com.example.starter.feed.api;

import com.example.starter.feed.error.FeedBusinessException;
import com.example.starter.feed.error.IdempotencyConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应；合规校验拒绝属于业务结果（200 + REJECTED 原因列表），不走该处理器。
 */
@RestControllerAdvice
public class FeedExceptionHandler {

    /** 同键异参：409 Conflict。 */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    /** 业务前置条件不满足：400。 */
    @ExceptionHandler(FeedBusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(FeedBusinessException ex) {
        return ResponseEntity.badRequest().body(body("BUSINESS_REJECTED", ex.getMessage()));
    }

    /** Bean Validation 失败：400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", message));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of("timestamp", Instant.now().toString(), "code", code, "message", message);
    }
}
