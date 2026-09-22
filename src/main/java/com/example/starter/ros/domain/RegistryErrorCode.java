package com.example.starter.ros.domain;

/**
 * 注册中心业务错误码，与 HTTP 状态码共同区分不同失败语义。
 */
public enum RegistryErrorCode {
    /** 请求参数校验失败 */
    VALIDATION_ERROR(400),
    /** 节点不存在 */
    NODE_NOT_FOUND(404),
    /** nodeId 重复注册，或崩溃/终结节点不允许该操作 */
    NODE_ALREADY_REGISTERED(409),
    /** 节点已终结（FINALIZED），不允许再迁移 */
    NODE_FINALIZED(410),
    /** 节点已异常退出（alive=0），不允许业务操作 */
    NODE_CRASHED(410),
    /** Service/Action 同名互斥冲突 */
    RESOURCE_CONFLICT(409),
    /** 非法相邻生命周期迁移 */
    ILLEGAL_TRANSITION(422),
    /** 乐观版本冲突 */
    VERSION_CONFLICT(409),
    /** 同一 requestId 异参重放 */
    IDEMPOTENCY_PARAM_MISMATCH(409),
    /** 同 requestId 并发请求仍在处理中，重放等待超时 */
    IDEMPOTENCY_INFLIGHT_TIMEOUT(409),
    /** 检查点不存在或已存在等检查点错误 */
    CHECKPOINT_ERROR(409);

    private final int httpStatus;

    RegistryErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
