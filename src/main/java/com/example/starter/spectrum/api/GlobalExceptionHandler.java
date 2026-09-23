package com.example.starter.spectrum.api;

import com.example.starter.spectrum.domain.ConflictReport;
import com.example.starter.spectrum.service.IdempotencyConflictException;
import com.example.starter.spectrum.service.InvalidLeaseRequestException;
import com.example.starter.spectrum.service.LeaseConflictException;
import com.example.starter.spectrum.service.LeaseNotFoundException;
import com.example.starter.spectrum.service.LeaseServiceException;
import com.example.starter.spectrum.service.VersionConflictException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：{@code {code, message, report?}}，冲突类错误携带解释报告。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务冲突（租约冲突、版本冲突、幂等指纹冲突）。 */
    @ExceptionHandler(LeaseConflictException.class)
    public ResponseEntity<Map<String, Object>> handleLeaseConflict(LeaseConflictException ex) {
        Map<String, Object> body = baseBody(ex);
        body.put("report", ex.getReport());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /** 其他携带状态码的业务异常。 */
    @ExceptionHandler(LeaseServiceException.class)
    public ResponseEntity<Map<String, Object>> handleService(LeaseServiceException ex) {
        return ResponseEntity.status(ex.getStatus()).body(baseBody(ex));
    }

    /** Bean 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", message));
    }

    /** 简单类型参数校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", ex.getMessage()));
    }

    /** 请求体无法解析。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", "malformed request body"));
    }

    private static Map<String, Object> baseBody(LeaseServiceException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        return body;
    }
}
