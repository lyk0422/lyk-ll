package com.example.starter.ct.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.example.starter.ct.CtException;

/**
 * 统一错误响应：{"error":{"code":...,"message":...}}，错误码稳定。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 错误体。 */
    public record ErrorBody(String code, String message) {
    }

    /** 统一错误响应。 */
    public record ErrorResponse(ErrorBody error) {
    }

    @ExceptionHandler(CtException.class)
    public ResponseEntity<ErrorResponse> handleCt(CtException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ErrorResponse(new ErrorBody(ex.code(), ex.getMessage())));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(new ErrorBody("BAD_REQUEST", ex.getMessage())));
    }
}
