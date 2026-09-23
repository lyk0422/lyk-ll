package com.example.starter.costmap.web;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码与稳定错误语义。错误响应统一为
 * {@code {"error": code, "message": ..., "requestId": ...}}，code 对客户端稳定。
 */
public enum ErrorCode {

    /** 请求体或参数非法（400）。 */
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "BAD_REQUEST"),

    /** 区域为空、反转或越出地图边界（400）。 */
    INVALID_REGION(HttpStatus.BAD_REQUEST, "INVALID_REGION"),

    /** 指定的地图版本/快照不存在（404）。 */
    MAP_NOT_FOUND(HttpStatus.NOT_FOUND, "MAP_NOT_FOUND"),

    /** 快照不存在（404）。 */
    SNAPSHOT_NOT_FOUND(HttpStatus.NOT_FOUND, "SNAPSHOT_NOT_FOUND"),

    /** 同一 requestId 已应用且参数不同（409）。 */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"),

    /** 地图/快照 ID 冲突，已存在（409）。 */
    VERSION_CONFLICT(HttpStatus.CONFLICT, "VERSION_CONFLICT"),

    /** 并发更新乐观锁冲突（409）。 */
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "CONCURRENT_UPDATE"),

    /** 服务端内部错误（500）。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

    private final HttpStatus httpStatus;
    private final String code;

    ErrorCode(HttpStatus httpStatus, String code) {
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
