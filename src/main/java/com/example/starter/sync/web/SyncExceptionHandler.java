package com.example.starter.sync.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.example.starter.sync.SyncErrorCode;
import com.example.starter.sync.SyncException;

/**
 * 统一错误响应：业务错误携带稳定错误码，便于客户端定位。
 */
@RestControllerAdvice
public class SyncExceptionHandler {

    /**
     * 错误响应体。
     */
    public record ErrorBody(String code, String message) {
    }

    /**
     * 业务异常按错误码映射 HTTP 状态。
     */
    @ExceptionHandler(SyncException.class)
    public ResponseEntity<ErrorBody> handleSync(SyncException e) {
        HttpStatus status = switch (e.code()) {
            case FRAME_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FRAME_ALREADY_COMMITTED -> HttpStatus.CONFLICT;
            case INVALID_DRIFT_VERSION, BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case MISSING_INPUT, OUT_OF_WINDOW, CANNOT_INTERPOLATE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(new ErrorBody(e.code().name(), e.getMessage()));
    }

    /**
     * 请求体校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(new ErrorBody(SyncErrorCode.BAD_REQUEST.name(),
                "request validation failed: " + e.getBindingResult().getFieldErrors().stream()
                        .map(fe -> fe.getField() + " " + fe.getDefaultMessage()).toList()));
    }

    /**
     * 请求体无法解析（如非法枚举值）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ErrorBody(SyncErrorCode.BAD_REQUEST.name(),
                "request body is not readable"));
    }
}
