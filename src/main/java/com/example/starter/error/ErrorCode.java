package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 稳定且彼此可区分的业务错误码；每个错误码固定映射一个 HTTP 状态。
 */
public enum ErrorCode {

    /** 地图版本不存在。 */
    MAP_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 地图版本已废弃，不再接受新路线请求（已绑定请求不受影响）。 */
    MAP_VERSION_DEPRECATED(HttpStatus.CONFLICT),
    /** 地图版本标识已存在，重复发布被拒绝。 */
    MAP_VERSION_ALREADY_EXISTS(HttpStatus.CONFLICT),
    /** 匹配点附近存在路段但全部已失效。 */
    SEGMENT_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY),
    /** 起点附近没有任何候选路段。 */
    NO_ORIGIN_CANDIDATE(HttpStatus.UNPROCESSABLE_ENTITY),
    /** 终点附近没有任何候选路段。 */
    NO_DESTINATION_CANDIDATE(HttpStatus.UNPROCESSABLE_ENTITY),
    /** 起终点候选之间拓扑不连通。 */
    PATH_DISCONNECTED(HttpStatus.UNPROCESSABLE_ENTITY),
    /** 相同 requestId 携带不同参数重放。 */
    REQUEST_CONFLICT(HttpStatus.CONFLICT),
    /** 路线请求不存在。 */
    REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
