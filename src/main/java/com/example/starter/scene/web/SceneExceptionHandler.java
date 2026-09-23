package com.example.starter.scene.web;

import com.example.starter.scene.exception.RequestConflictException;
import com.example.starter.scene.exception.SceneValidationException;
import com.example.starter.scene.exception.VersionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Planning Scene 接口异常到 HTTP 响应的统一映射。
 */
@RestControllerAdvice
public class SceneExceptionHandler {

    /**
     * 请求内容非法：400。
     */
    @ExceptionHandler(SceneValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(SceneValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "VALIDATION_ERROR",
                "message", ex.getMessage()));
    }

    /**
     * 请求体无法解析：400。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "VALIDATION_ERROR",
                "message", "request body is missing or malformed"));
    }

    /**
     * 期望版本过期：409，携带当前版本与变化对象/关节列表。
     */
    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleVersionConflict(VersionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "VERSION_CONFLICT",
                "message", ex.getMessage(),
                "currentVersion", ex.getCurrentVersion(),
                "changedObjects", ex.getChangedObjects(),
                "changedJoints", ex.getChangedJoints()));
    }

    /**
     * 幂等键同键异参：409。
     */
    @ExceptionHandler(RequestConflictException.class)
    public ResponseEntity<Map<String, Object>> handleRequestConflict(RequestConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "REQUEST_CONFLICT",
                "message", ex.getMessage(),
                "requestId", ex.getRequestId()));
    }

    /**
     * 其他未预期错误：500，不向外暴露内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", "internal server error"));
    }
}
