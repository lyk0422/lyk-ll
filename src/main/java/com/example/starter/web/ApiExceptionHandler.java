package com.example.starter.web;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.error.BusinessException;

/**
 * 全局异常处理：统一错误响应结构 {code, message}。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 错误响应体。 */
    public record ErrorBody(String code, String message) {
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorBody> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody("VALIDATION_FAILED", message));
    }
}
