package com.example.starter.costmap.error;

import org.springframework.http.HttpStatus;

/**
 * 稳定的业务错误码，与 HTTP 状态一一对应。
 */
public enum ErrorCode {

    /** 请求参数非法（缺字段、数组长度不符、代价值越界等）。 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    /** 区域越界或尺寸非法。 */
    INVALID_REGION(HttpStatus.BAD_REQUEST),
    /** 地图不存在。 */
    MAP_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 地图版本不存在。 */
    VERSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 快照不存在。 */
    SNAPSHOT_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 同一 requestId 以不同参数重复提交。 */
    REQUEST_CONFLICT(HttpStatus.CONFLICT),
    /** 同一地图同一版本的快照已发布。 */
    SNAPSHOT_CONFLICT(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
