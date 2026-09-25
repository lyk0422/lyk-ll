package com.example.starter.web;

import com.example.starter.service.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一错误语义：业务异常映射为对应 HTTP 状态码与 JSON 错误体。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 业务异常：404/409/422。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("error", e.getClass().getSimpleName(), "message", e.getMessage()));
    }

    /**
     * 参数校验失败：400。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "ValidationException", "message", message));
    }
}
