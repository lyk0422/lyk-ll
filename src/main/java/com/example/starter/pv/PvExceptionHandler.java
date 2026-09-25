package com.example.starter.pv;

import java.time.Instant;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常按语义映射 HTTP 状态，校验异常返回 400。
 */
@RestControllerAdvice
public class PvExceptionHandler {

    /**
     * 统一错误响应体。
     */
    public record ErrorBody(String error, String message, Instant timestamp) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApiException(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(new ErrorBody(e.status().getReasonPhrase(), e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody(HttpStatus.BAD_REQUEST.getReasonPhrase(), message, Instant.now()));
    }
}
