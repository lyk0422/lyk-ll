package com.example.starter.web;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.starter.support.BizException;

/**
 * 全局异常处理：统一错误响应体 {code, message, timestamp}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 统一错误响应体。
     */
    public record ErrorBody(String code, String message, Instant timestamp) {
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorBody> handleBiz(BizException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorBody(e.getCode(), e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("请求参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody("VALIDATION_FAILED", message, Instant.now()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorBody> handleBadPayload(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody("BAD_REQUEST", "请求格式不合法", Instant.now()));
    }
}
