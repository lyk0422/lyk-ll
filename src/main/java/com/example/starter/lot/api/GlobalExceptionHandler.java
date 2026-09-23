package com.example.starter.lot.api;

import com.example.starter.lot.service.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一错误响应：{"code": ..., "message": ..., "path": ..., "timestamp": ...}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常按其携带的状态码返回。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getHttpStatus()).body(body(ex.getCode(), ex.getMessage(), request));
    }

    /** Bean 校验失败返回 400，错误码 VALIDATION_FAILED，并聚合字段信息。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::fieldMessage)
                .collect(Collectors.joining("; "));
        Map<String, Object> body = body("VALIDATION_FAILED",
                detail.isBlank() ? "请求参数校验失败" : detail, request);
        return ResponseEntity.badRequest().body(body);
    }

    /** 请求体无法解析（JSON 格式错误、数量非数字等）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex,
                                                                HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(body("MALFORMED_REQUEST", "请求体格式不正确", request));
    }

    private String fieldMessage(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private Map<String, Object> body(String code, String message, HttpServletRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        map.put("path", request.getRequestURI());
        map.put("timestamp", OffsetDateTime.now().toString());
        return map;
    }
}
