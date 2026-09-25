package com.example.starter.legality.api;

import com.example.starter.legality.service.BadRequestException;
import com.example.starter.legality.service.BatchFinalizedException;
import com.example.starter.legality.service.IdempotentConflictException;
import com.example.starter.legality.service.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 合规服务异常到 HTTP 状态码的映射。
 */
@RestControllerAdvice
public class LegalityExceptionHandler {

    /** 同键异参冲突 → 409，且不写入记录。 */
    @ExceptionHandler(IdempotentConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IdempotentConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("IDEMPOTENT_CONFLICT", ex.getMessage()));
    }

    /** 批次已封板终态，改签须走新校验批次 → 409。 */
    @ExceptionHandler(BatchFinalizedException.class)
    public ResponseEntity<Map<String, Object>> handleFinalized(BatchFinalizedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("BATCH_FINALIZED", ex.getMessage()));
    }

    /** 业务前置条件不满足 → 400。 */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("BAD_REQUEST", ex.getMessage()));
    }

    /** 资源不存在 → 404。 */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", ex.getMessage()));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of(
                "code", code,
                "message", message,
                "timestamp", OffsetDateTime.now().toString());
    }
}
