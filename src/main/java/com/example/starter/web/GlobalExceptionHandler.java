package com.example.starter.web;

import com.example.starter.domain.BusinessException;
import com.example.starter.domain.VersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：业务异常携带稳定 code，校验失败返回字段明细。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(VersionConflictException ex,
                                                              HttpServletRequest request) {
        Map<String, Object> body = baseBody("VERSION_CONFLICT", ex.getMessage(), request);
        body.put("expectedVersion", ex.getExpectedVersion());
        body.put("actualVersion", ex.getActualVersion());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex,
                                                              HttpServletRequest request) {
        HttpStatus status = "TRACK_NOT_FOUND".equals(ex.getCode())
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(baseBody(ex.getCode(), ex.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                HttpServletRequest request) {
        Map<String, Object> body = baseBody("VALIDATION_FAILED", "请求参数校验失败", request);
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(baseBody("MALFORMED_REQUEST", "请求体无法解析", request));
    }

    private Map<String, Object> baseBody(String code, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("timestamp", Instant.now().toEpochMilli());
        return body;
    }
}
