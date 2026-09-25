package com.example.starter.forestry.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.forestry.support.ForestryException;

/**
 * 全局异常处理：业务异常映射为携带稳定错误码的响应体。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 错误响应体。 */
    public record ErrorBody(String code, String message) {
    }

    @ExceptionHandler(ForestryException.class)
    public ResponseEntity<ErrorBody> handleForestry(ForestryException ex) {
        return ResponseEntity.status(ex.httpStatus())
                .body(new ErrorBody(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(new ErrorBody("VALIDATION_FAILED", message));
    }
}
