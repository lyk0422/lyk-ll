package com.example.starter.planningscene.web;

import com.example.starter.planningscene.dto.ApiErrorResponse;
import com.example.starter.planningscene.dto.ConflictResponse;
import com.example.starter.planningscene.service.SceneException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 场景服务异常到 HTTP 响应的映射。
 */
@RestControllerAdvice
public class SceneExceptionHandler {

    /** 业务规则与版本/幂等冲突。 */
    @ExceptionHandler(SceneException.class)
    public ResponseEntity<?> handle(SceneException ex) {
        if (ex instanceof SceneException.VersionConflict conflict) {
            return ResponseEntity.status(409).body(new ConflictResponse(
                    "VERSION_CONFLICT", conflict.getCurrentVersion(), conflict.getExpectedVersion(),
                    conflict.getChangedObjectIds(), conflict.getChangedJointNames()));
        }
        if (ex instanceof SceneException.IdempotencyConflict) {
            return ResponseEntity.status(409).body(new ApiErrorResponse("IDEMPOTENCY_CONFLICT", ex.getMessage()));
        }
        return ResponseEntity.status(ex.httpStatus())
                .body(new ApiErrorResponse(ex.errorCode(), ex.getMessage()));
    }

    /** Bean Validation 失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest().body(new ApiErrorResponse("VALIDATION_FAILED", message));
    }
}
