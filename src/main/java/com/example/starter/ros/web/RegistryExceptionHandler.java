package com.example.starter.ros.web;

import com.example.starter.ros.api.ErrorResponse;
import com.example.starter.ros.domain.RegistryErrorCode;
import com.example.starter.ros.domain.RegistryException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 注册中心异常到 HTTP 响应的统一映射。
 */
@RestControllerAdvice
public class RegistryExceptionHandler {

    @ExceptionHandler(RegistryException.class)
    public ResponseEntity<ErrorResponse> handleRegistry(RegistryException ex, HttpServletRequest request) {
        RegistryErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.httpStatus())
                .body(new ErrorResponse(code.name(), ex.getMessage(), request.getRequestURI(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("validation failed");
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(RegistryErrorCode.VALIDATION_ERROR.name(), message,
                        request.getRequestURI(), Instant.now()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(RegistryErrorCode.VALIDATION_ERROR.name(), ex.getMessage(),
                        request.getRequestURI(), Instant.now()));
    }

    /** 兜底 500，避免暴露堆栈。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "internal server error",
                        request.getRequestURI(), Instant.now()));
    }
}
