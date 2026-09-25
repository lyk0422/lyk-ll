package com.example.starter.deicing.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：{@code {timestamp, status, errorCode, message}}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 错误体构造。
     */
    public record ErrorBody(long timestamp, int status, String errorCode, String message) {
    }

    /**
     * @param ex 业务异常
     * @return 按异常携带状态码返回
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorBody(Instant.now().toEpochMilli(), ex.getStatus().value(),
                        ex.getErrorCode(), ex.getMessage()));
    }

    /**
     * @param ex Bean 校验失败
     * @return 400 与首个字段错误
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "请求参数校验失败"
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        return ResponseEntity.badRequest()
                .body(new ErrorBody(Instant.now().toEpochMilli(), 400, "VALIDATION_FAILED", message));
    }

    /**
     * @param ex 请求体不可读
     * @return 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody(Instant.now().toEpochMilli(), 400, "MALFORMED_JSON", "请求体格式错误"));
    }

    /**
     * @param ex 兜底异常
     * @return 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("status", 500);
        body.put("errorCode", "INTERNAL_ERROR");
        body.put("message", "服务内部错误");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
