package com.example.starter.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常按约定结构返回，未知异常兜底为 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 错误响应体。
     *
     * @param code    业务错误码
     * @param message 错误描述
     * @param details 可选明细（如冲突详情），无明细时为 null
     */
    public record ErrorBody(String code, String message, Object details) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorBody(ex.getCode(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleOther(Exception ex) {
        return ResponseEntity.status(500)
                .body(new ErrorBody("INTERNAL_ERROR", ex.getMessage(), null));
    }
}
