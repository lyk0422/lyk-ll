package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.child.service.BusinessRejectionException;
import com.example.starter.cosmetic.service.ConflictException;
import com.example.starter.cosmetic.service.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 统一异常到 HTTP 状态的映射：404 不存在，409 业务冲突（含同键异参），
 * 422 业务拒绝（儿童分龄通道），400 参数非法。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 404。 */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", e.getMessage()));
    }

    /** 409。 */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(e.code(), e.getMessage()));
    }

    /** 422 业务拒绝（儿童分龄通道：版本不一致、状态不允许、伪装简易变更等）。 */
    @ExceptionHandler(BusinessRejectionException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessRejection(BusinessRejectionException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body(e.code(), e.getMessage()));
    }

    /** 400 参数校验。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", message));
    }

    /** 400 其他非法参数。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body("INVALID_ARGUMENT", e.getMessage()));
    }

    /**
     * 409 数据约束冲突：并发下的幂等键/版本/有效槽唯一竞争等。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("DATA_INTEGRITY_CONFLICT", "并发数据冲突，请重试"));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of("code", code, "message", String.valueOf(message), "atUtc", Instant.now().toString());
    }
}
