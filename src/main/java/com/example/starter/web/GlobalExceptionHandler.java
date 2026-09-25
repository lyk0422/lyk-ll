package com.example.starter.web;

import com.example.starter.error.ApiException;
import com.example.starter.web.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：统一错误响应体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常（404/409 等）。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        return ResponseEntity.status(e.httpStatus())
                .body(new ApiError(e.getCode(), e.getMessage(), e.getCurrentStatus()));
    }

    /**
     * 参数校验失败（400）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("VALIDATION_FAILED", "请求参数校验失败", null));
    }
}
