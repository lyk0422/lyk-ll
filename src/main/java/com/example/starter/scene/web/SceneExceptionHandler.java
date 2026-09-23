package com.example.starter.scene.web;

import com.example.starter.scene.exception.SceneConflictException;
import com.example.starter.scene.exception.SceneNotFoundException;
import com.example.starter.scene.exception.SceneValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Planning Scene 异常到 HTTP 状态码与错误体的统一映射。
 */
@RestControllerAdvice
public class SceneExceptionHandler {

    @ExceptionHandler(SceneConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(SceneConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        body.put("expectedVersion", ex.getExpectedVersion());
        body.put("currentVersion", ex.getCurrentVersion());
        body.put("changedObjectIds", ex.getChangedObjectIds());
        body.put("changedJointNames", ex.getChangedJointNames());
        body.put("robotPoseChanged", ex.isRobotPoseChanged());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(SceneValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(SceneValidationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_OPERATION");
        body.put("message", ex.getMessage());
        body.put("operationIndex", ex.getOperationIndex());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(SceneNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(SceneNotFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "SNAPSHOT_NOT_FOUND");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBeanValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_REQUEST");
        body.put("message", ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("request validation failed"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
