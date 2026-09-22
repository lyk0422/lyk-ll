package com.example.starter.sensor.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 同步 API 异常映射：非法参数返回 400 与稳定错误体。
 */
@RestControllerAdvice
public class SensorExceptionAdvice {

    /** 传感器枚举不合法等非法参数。 */
    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", HttpStatus.BAD_REQUEST.value(), "error", ex.getMessage()));
    }
}
