package com.example.starter.trajectory.web;

import com.example.starter.trajectory.service.ConflictingRequestException;
import com.example.starter.trajectory.service.DuplicateRuleVersionException;
import com.example.starter.trajectory.service.PublishedRuleImmutableException;
import com.example.starter.trajectory.service.RuleVersionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务异常与参数校验异常到 HTTP 状态码的映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 同键异参：409 Conflict。
     */
    @ExceptionHandler(ConflictingRequestException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictingRequestException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 规则版本不存在或已发布不可修改：404 Not Found。
     */
    @ExceptionHandler(RuleVersionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(RuleVersionNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * 已发布配置不可原地修改，或规则版本重复创建：409 Conflict。
     */
    @ExceptionHandler(PublishedRuleImmutableException.class)
    public ResponseEntity<Map<String, Object>> handleImmutable(PublishedRuleImmutableException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 规则版本号重复：409 Conflict。
     */
    @ExceptionHandler(DuplicateRuleVersionException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateRuleVersionException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 请求参数非法：400 Bad Request。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("invalid request");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 非法业务入参（轨迹不满足前置约束等）：400 Bad Request。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
