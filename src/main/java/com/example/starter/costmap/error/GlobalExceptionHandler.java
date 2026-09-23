package com.example.starter.costmap.error;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器：把业务异常与常见请求错误转换为稳定错误响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CostmapException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(CostmapException ex) {
        return ResponseEntity.status(ex.code().status())
                .body(new ErrorResponse(ex.code().name(), ex.getMessage()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(), ex.getMessage()));
    }
}
