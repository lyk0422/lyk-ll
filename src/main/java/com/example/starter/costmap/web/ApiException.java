package com.example.starter.costmap.web;

import org.springframework.http.HttpStatus;

/**
 * 对外稳定错误：携带稳定错误码与 HTTP 状态，错误响应体结构固定。
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    private ApiException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static ApiException invalidRegion(String message) {
        return new ApiException("INVALID_REGION", HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException invalidRequest(String message) {
        return new ApiException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException mapNotFound(String versionId) {
        return new ApiException("MAP_NOT_FOUND", HttpStatus.NOT_FOUND, "map version not found: " + versionId);
    }

    public static ApiException snapshotNotFound(String snapshotId) {
        return new ApiException("SNAPSHOT_NOT_FOUND", HttpStatus.NOT_FOUND, "snapshot not found: " + snapshotId);
    }

    public static ApiException versionConflict(long expected, long actual) {
        return new ApiException("VERSION_CONFLICT", HttpStatus.CONFLICT,
                "expected seq " + expected + " but actual seq " + actual);
    }

    public static ApiException idempotencyConflict(String requestId) {
        return new ApiException("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT,
                "requestId " + requestId + " was already applied with different parameters");
    }

    public static ApiException mapExists(String versionId) {
        return new ApiException("MAP_EXISTS", HttpStatus.CONFLICT, "map version already exists: " + versionId);
    }

    public static ApiException snapshotExists(String snapshotId) {
        return new ApiException("SNAPSHOT_EXISTS", HttpStatus.CONFLICT,
                "snapshot already exists: " + snapshotId);
    }

    public static ApiException viewNotFound(String viewId) {
        return new ApiException("VIEW_NOT_FOUND", HttpStatus.NOT_FOUND, "restored view not found: " + viewId);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
