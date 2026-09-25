package com.example.starter.web;

import com.example.starter.dto.ApiDtos;
import com.example.starter.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常按状态码返回统一错误体。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiDtos.ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleBadBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiDtos.ErrorResponse("请求体格式非法"));
    }
}
