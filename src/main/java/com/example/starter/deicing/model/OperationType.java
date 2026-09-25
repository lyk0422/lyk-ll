package com.example.starter.deicing.model;

/**
 * 幂等操作类型；幂等键为 (requestId, operation)。
 */
public enum OperationType {

    /** 申请除冰。 */
    APPLY,

    /** 出队确认。 */
    DISPATCH,

    /** 作业开始。 */
    START,

    /** 作业完成。 */
    COMPLETE,

    /** 前序延误导致的预计起飞时刻更新。 */
    DELAY,

    /** HOT 风险后确认重新除冰排定。 */
    RESCHEDULE,

    /** 起飞回报与判定。 */
    DEPART,

    /** 取消。 */
    CANCEL
}
