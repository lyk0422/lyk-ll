package com.example.starter.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.support.ApiException;

/**
 * 全局异常处理：业务异常与参数校验异常转为携带 errorCode 的 JSON 响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 统一错误载荷。 */
    public record ErrorBody(String errorCode, String message) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("errorCode", ex.getErrorCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Map.of(
                "errorCode", "VALIDATION_FAILED",
                "message", detail.isEmpty() ? "请求参数校验失败" : detail));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    /** 预留：保留有序载荷构造能力。 */
    @SuppressWarnings("unused")
    private Map<String, Object> ordered(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
