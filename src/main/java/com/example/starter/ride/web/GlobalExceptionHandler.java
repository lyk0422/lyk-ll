package com.example.starter.ride.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.ride.ApiResult;
import com.example.starter.ride.BusinessRuleException;

/**
 * 将业务规则异常映射为对应 HTTP 状态码响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiResult> handleBusiness(BusinessRuleException ex) {
        return ResponseEntity.status(HttpStatus.valueOf(ex.getHttpStatus()))
                .body(new ApiResult(ex.getHttpStatus(), ex.getMessage(), null));
    }
}
