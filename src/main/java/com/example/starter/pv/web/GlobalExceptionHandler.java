package com.example.starter.pv.web;

import java.time.Instant;

import com.example.starter.pv.service.ApiException;
import com.example.starter.pv.service.IdempotentReplayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应：{timestamp,status,error,message}；同键同参重放直接返回首次结果。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 错误体。
     */
    public record ErrorBody(Instant timestamp, int status, String error, String message) {
    }

    /** 同键同参重放：返回首次成功结果（HTTP 200，区别于首次 201）。 */
    @ExceptionHandler(IdempotentReplayException.class)
    public ResponseEntity<Object> handleReplay(IdempotentReplayException ex) {
        return ResponseEntity.ok(ex.getReplayResponse());
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
        return build(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleOther(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误");
    }

    private ResponseEntity<ErrorBody> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(
                Instant.now(), status.value(), status.getReasonPhrase(), message));
    }
}
