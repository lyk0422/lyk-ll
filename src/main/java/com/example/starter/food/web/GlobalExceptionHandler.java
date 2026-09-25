package com.example.starter.food.web;

import com.example.starter.food.service.BusinessException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应：{ timestamp, status, error, message }。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        return build(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> field(fe))
                .orElse("请求参数校验失败");
        return build(400, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(400, "请求体无法解析或字段类型不正确");
    }

    private static String field(FieldError fe) {
        return fe.getField() + " " + fe.getDefaultMessage();
    }

    private ResponseEntity<Map<String, Object>> build(int status, String message) {
        HttpStatus httpStatus = HttpStatus.resolve(status) == null ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.valueOf(status);
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status,
                "error", httpStatus.getReasonPhrase(),
                "message", message == null ? "" : message));
    }
}
