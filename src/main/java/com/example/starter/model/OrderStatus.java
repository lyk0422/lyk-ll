package com.example.starter.model;

/**
 * 调度单状态机：DISPATCHED 在途 → GENERATING 发电中；超时未确认升级 GENERATION_ABNORMAL；
 * 市电恢复转 RELEASED；结案 CLOSED 为终态。
 */
public enum OrderStatus {
    /** 已派单，油机在途，等待发电确认。 */
    DISPATCHED,
    /** 油机已到位启动并回传发电确认。 */
    GENERATING,
    /** 发电确认超时（30 分钟，恰满判超时），已释放原油机，允许改派。 */
    GENERATION_ABNORMAL,
    /** 市电恢复，调度解除，油机已释放。 */
    RELEASED,
    /** 结案，终态，不再接受任何状态变更。 */
    CLOSED
}
