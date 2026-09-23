package com.example.starter.dr.web;

import com.example.starter.dr.ApiModels;
import com.example.starter.dr.DrBusinessException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：统一错误响应体与状态码（骨架，后续补充冲突等处理）。
 */
@RestControllerAdvice
public class DrExceptionHandler {

    @ExceptionHandler(DrBusinessException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleBusiness(DrBusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiModels.ErrorResponse(ex.getStatus().name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiModels.ErrorResponse("BAD_REQUEST", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiModels.ErrorResponse("BAD_REQUEST", ex.getMessage()));
    }
}
