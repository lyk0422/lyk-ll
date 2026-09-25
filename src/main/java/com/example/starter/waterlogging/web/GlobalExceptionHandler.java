package com.example.starter.waterlogging.web;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.waterlogging.exception.ApiException;

/**
 * 全局异常映射：业务异常携带的状态码与错误码原样返回。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public GlobalExceptionHandler(Clock waterClock) {
        this.clock = waterClock;
    }

    private final Clock clock;

    /** 业务异常。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiError(ex.getCode(), ex.getMessage(), LocalDateTime.now(clock)));
    }

    /** 数据库唯一约束兜底：同一资源并发唯一受理。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("RESOURCE_CONFLICT", "资源冲突或并发唯一受理失败", LocalDateTime.now(clock)));
    }

    /** 请求体校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("VALIDATION_FAILED", message, LocalDateTime.now(clock)));
    }
}
