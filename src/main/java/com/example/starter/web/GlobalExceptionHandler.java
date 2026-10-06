package com.example.starter.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.exception.BizException;

/**
 * 全局异常处理：业务异常按携带的状态码返回稳定错误码，参数校验失败返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 统一错误响应体。 */
    public record ErrorBody(String code, String message) {
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorBody> handleBiz(BizException e) {
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody("VALIDATION_FAILED", message));
    }
}
