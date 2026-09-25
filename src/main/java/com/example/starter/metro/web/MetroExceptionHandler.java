package com.example.starter.metro.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.metro.MetroException;

/**
 * 全局异常处理：业务异常按携带的状态码返回，参数错误返回 400。
 */
@RestControllerAdvice
public class MetroExceptionHandler {

    @ExceptionHandler(MetroException.class)
    public ResponseEntity<Map<String, Object>> handleMetro(MetroException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Map.of("error", e.getMessage(), "status", e.getStatus()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst().orElse("请求参数校验失败");
        return ResponseEntity.badRequest()
                .body(Map.of("error", message, "status", HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "请求报文无法解析", "status", HttpStatus.BAD_REQUEST.value()));
    }
}
