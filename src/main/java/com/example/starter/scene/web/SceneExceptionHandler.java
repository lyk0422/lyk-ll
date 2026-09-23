package com.example.starter.scene.web;

import com.example.starter.scene.service.SceneConflictException;
import com.example.starter.scene.service.SceneNotFoundException;
import com.example.starter.scene.service.SceneValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Planning Scene 异常到 HTTP 状态码的映射。
 */
@RestControllerAdvice
public class SceneExceptionHandler {

    /**
     * 版本过期/幂等参数冲突 -> 409 并携带变化对象与关节列表。
     */
    @ExceptionHandler(SceneConflictException.class)
    public ResponseEntity<Object> handleConflict(SceneConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getResponse());
    }

    /**
     * 请求非法 -> 400。
     */
    @ExceptionHandler(SceneValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(SceneValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /**
     * 历史版本等资源不存在 -> 404。
     */
    @ExceptionHandler(SceneNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(SceneNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
