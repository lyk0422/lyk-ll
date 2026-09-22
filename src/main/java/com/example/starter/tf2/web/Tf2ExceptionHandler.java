package com.example.starter.tf2.web;

import com.example.starter.tf2.service.Tf2Exception;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将业务异常映射为稳定的 JSON 错误响应与 HTTP 状态码。
 */
@RestControllerAdvice
public class Tf2ExceptionHandler {

    /** 业务错误：携带 errorCode/message，环检测额外返回 cycle 候选路径。 */
    @ExceptionHandler(Tf2Exception.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(Tf2Exception ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        if (ex instanceof Tf2Exception.CycleDetected cycle) {
            body.put("cycle", cycle.getCycle());
        }
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    /** 请求体校验失败：400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", "INVALID_REQUEST");
        body.put("message", "请求参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
