package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.service.ConflictException;
import com.example.starter.cosmetic.service.NotFoundException;
import com.example.starter.cosmetic.service.UnprocessableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常到 HTTP 状态的映射：404 不存在，409 幂等/并发冲突（含同键异参、版本冲突），
 * 422 业务拒绝（状态不允许、儿童分龄审查缺项零生效），400 参数非法。
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

    /** 422 业务拒绝（含缺项明细，一次列全）。 */
    @ExceptionHandler(UnprocessableException.class)
    public ResponseEntity<Map<String, Object>> handleUnprocessable(UnprocessableException e) {
        Map<String, Object> body = new LinkedHashMap<>(body(e.code(), e.getMessage()));
        if (!e.violations().isEmpty()) {
            body.put("violations", e.violations());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
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
