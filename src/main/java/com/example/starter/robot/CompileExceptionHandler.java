package com.example.starter.robot;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 编译接口统一异常处理。
 */
@RestControllerAdvice
public class CompileExceptionHandler {

    @ExceptionHandler(CompileException.class)
    public ResponseEntity<ErrorResponse> handleCompile(CompileException ex) {
        return ResponseEntity.status(ex.errorCode().status())
                .body(new ErrorResponse(ex.requestId(), ex.errorCode().name(), ex.getMessage(), ex.chain()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(resolveRequestId(ex), ErrorCode.INVALID_REQUEST.name(),
                        "请求参数校验失败", List.of()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(null, ErrorCode.INVALID_REQUEST.name(),
                        "请求参数校验失败", List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(null, ErrorCode.INVALID_REQUEST.name(),
                        "请求体不是合法 JSON", List.of()));
    }

    private String resolveRequestId(MethodArgumentNotValidException ex) {
        try {
            Object target = ex.getBindingResult().getTarget();
            if (target instanceof CompileRequest request) {
                return request.requestId();
            }
        } catch (Exception ignored) {
            // 绑定失败时请求对象可能不可用，按未知 requestId 处理
        }
        return null;
    }
}
