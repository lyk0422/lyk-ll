package com.example.starter.audit.api;

import com.example.starter.audit.AuditApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：版本缺失等校验错误返回 422，同键异参返回 409。
 */
@RestControllerAdvice
public class AuditExceptionHandler {

    /** Bean Validation 失败（含必填版本缺失）：422。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "参数校验失败", fields);
    }

    /** 业务语义异常：使用异常自带状态码。 */
    @ExceptionHandler(AuditApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(AuditApiException ex) {
        return build(HttpStatus.valueOf(ex.getStatus()), ex.getMessage(), null);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message, Map<String, String> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (fields != null) {
            body.put("fields", fields);
        }
        return ResponseEntity.status(status).body(body);
    }
}
