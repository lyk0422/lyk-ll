package com.example.starter.lc;

/**
 * 业务错误码，随 4xx 响应体一并返回。
 */
public enum LcErrorCode {

    /** 通用参数/状态非法。 */
    INVALID_REQUEST,

    /** 信用证编号已存在。 */
    LC_ALREADY_EXISTS,

    /** 信用证或交单批次不存在。 */
    NOT_FOUND,

    /** 同 requestId 但业务参数与首次不一致。 */
    IDEMPOTENCY_CONFLICT,

    /** 同一信用证已有审单中的交单批次。 */
    ALREADY_UNDER_REVIEW,

    /** 交单晚于信用证有效期。 */
    PRESENTATION_EXPIRED,

    /** 拒付通知晚于第 5 个银行工作日 17:00（UTC），拒付无效。 */
    REFUSAL_TOO_LATE,

    /** 拒付后又试图补充新不符点。 */
    DISCREPANCIES_LOCKED,

    /** 放弃声明未覆盖全部已列不符点（部分放弃无效）。 */
    PARTIAL_WAIVER,

    /** 当前单据状态不允许该操作。 */
    ILLEGAL_STATE,

    /** 可用额度不足，整笔拒绝付款。 */
    INSUFFICIENT_AVAILABLE_AMOUNT
}
