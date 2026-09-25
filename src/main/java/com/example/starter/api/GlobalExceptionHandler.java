package com.example.starter.api;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.support.ApiError;
import com.example.starter.support.BusinessException;

/**
 * 全局异常处理：业务异常按其携带状态码返回；参数校验返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DUPLICATE_KEY", "唯一约束冲突，请求与已有数据重复"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("VALIDATION_FAILED", message));
    }
}
