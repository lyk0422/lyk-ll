package com.example.starter.web;

import java.time.Instant;
import java.util.stream.Collectors;

import com.example.starter.service.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：统一错误信封 {timestamp,status,code,message}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常（404/409/422）。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorBody(ex.getStatus().value(), ex.getCode(), ex.getMessage()));
    }

    /** 请求体校验失败 → 400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ErrorBody(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", detail));
    }

    /** 请求体不可解析（缺字段、类型错误等）→ 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody(HttpStatus.BAD_REQUEST.value(), "MALFORMED_REQUEST",
                        "请求体格式错误或字段类型不匹配"));
    }

    private static String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    /**
     * 统一错误响应体。
     */
    public record ErrorBody(int status, String code, String message, Instant timestamp) {
        public ErrorBody(int status, String code, String message) {
            this(status, code, message, Instant.now());
        }
    }
}
