package com.example.starter.ventilation.web;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.ventilation.ServiceException;
import com.example.starter.ventilation.TimeService;

/**
 * 统一错误响应：4xx 冲突/校验说明对客户端可见；未预期错误不泄露堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final TimeService timeService;

    public GlobalExceptionHandler(TimeService timeService) {
        this.timeService = timeService;
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Map<String, Object>> handleService(ServiceException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", ISO.format(timeService.now()));
        body.put("status", ex.getStatus().value());
        body.put("error", ex.getStatus().name());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", ISO.format(timeService.now()));
        body.put("status", 400);
        body.put("error", "BAD_REQUEST");
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        body.put("message", message);
        return ResponseEntity.status(400).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", ISO.format(timeService.now()));
        body.put("status", 500);
        body.put("error", "INTERNAL_ERROR");
        return ResponseEntity.status(500).body(body);
    }
}
