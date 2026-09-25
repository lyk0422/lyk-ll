package com.example.starter.reinsurance.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码（与 HTTP 状态码配合使用）。
 */
public enum ErrorCode {

    /** 入参校验失败。 */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),

    /** 合约/保单/赔案/账单不存在。 */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 状态冲突：如终态后再操作、待确认账单已被异议却要求确认。 */
    ILLEGAL_STATE(HttpStatus.CONFLICT),

    /** 同 requestId 但业务参数指纹不一致。 */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),

    /** 并发更新失败（乐观锁/行锁冲突），调用方可重试。 */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),

    /** 余额不足：结案冲销会使摊回账户为负（业务上不应出现，出现即数据错误）。 */
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public int statusValue() {
        return httpStatus.value();
    }
}
