package com.example.starter.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常按状态码返回统一错误体，参数校验失败返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 统一错误响应体。
     *
     * @param error   错误类别
     * @param message 错误描述
     */
    public record ErrorBody(String error, String message) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ErrorBody(ex.status().name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(new ErrorBody("BAD_REQUEST", String.join("; ", details)));
    }
}
