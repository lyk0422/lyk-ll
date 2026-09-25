package com.example.starter.domain;

/**
 * 调度单状态。
 */
public enum OrderStatus {

    /** 已生成待派单（内部瞬态，正常流程内立即完成油机分配）。 */
    PENDING,
    /** 油机已分配、在途未确认。 */
    DISPATCHED,
    /** 油机到位并回传发电确认。 */
    GENERATING,
    /** 发电确认超时（派单后 30 分钟恰满仍未确认）升级的异常态，允许改派。 */
    GENERATION_ABNORMAL,
    /** 市电恢复，调度单解除并释放油机（终态）。 */
    RELEASED,
    /** 调度单结案（终态）。 */
    CLOSED
}
