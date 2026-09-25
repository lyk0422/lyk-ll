package com.example.starter.schedule;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 统一异常出口：业务异常按状态码返回，参数校验失败返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ScheduleResponses.ErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ScheduleResponses.ErrorBody(
                        ex.status().name(), ex.getMessage(), ex.violations()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ScheduleResponses.ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ScheduleResponses.ErrorBody("BAD_REQUEST", message, List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ScheduleResponses.ErrorBody> handleOther(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ScheduleResponses.ErrorBody("INTERNAL_ERROR", ex.getMessage(), List.of()));
    }
}
