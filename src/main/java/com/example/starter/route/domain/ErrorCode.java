package com.example.starter.route.domain;

import org.springframework.http.HttpStatus;

/**
 * 稳定且彼此可区分的业务错误码。字符串值一经发布不再变更，作为跨版本错误契约。
 */
public enum ErrorCode {

    /** 发布/查询的地图版本不存在 */
    MAP_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 向已废弃版本提交新请求 */
    MAP_VERSION_DEPRECATED(HttpStatus.CONFLICT),

    /** 发布时版本号已存在（热更新只能发布新版本） */
    VERSION_ALREADY_EXISTS(HttpStatus.CONFLICT),

    /** 发布载荷拓扑非法：节点/路段 ID 重复或路段引用了不存在的端点 */
    MAP_TOPOLOGY_INVALID(HttpStatus.BAD_REQUEST),

    /** 端点搜索半径内的候选路段全部失效 */
    SEGMENT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 起点搜索半径内不存在任何候选路段 */
    START_NO_CANDIDATE(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 终点搜索半径内不存在任何候选路段 */
    END_NO_CANDIDATE(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 起终点均有有效候选，但有向图上不存在连通路径 */
    PATH_DISCONNECTED(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 同一 requestId 重放但提交参数与首次不同 */
    REPLAY_PARAM_CONFLICT(HttpStatus.CONFLICT),

    /** 查询的 requestId 不存在 */
    REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 入参校验失败 */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),

    /** 兜底内部错误 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
