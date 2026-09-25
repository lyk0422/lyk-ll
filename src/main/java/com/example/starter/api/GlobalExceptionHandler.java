package com.example.starter.api;

import com.example.starter.service.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常映射：业务冲突按异常携带的状态码（404/409）返回；参数校验失败返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(body(ex.getStatusCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(400, message));
    }

    private Map<String, Object> body(int status, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("status", status);
        map.put("error", message);
        return map;
    }
}
