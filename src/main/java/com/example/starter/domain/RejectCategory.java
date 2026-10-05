package com.example.starter.domain;

/**
 * 方案整单拒绝/业务异常分类码。
 */
public enum RejectCategory {
    /** 替代燃料热量替代率超过上限（恰40%合法）。 */
    RATE_EXCEEDED,
    /** 高氯硫燃料加权入窑氯总量超过限值。 */
    CHLORINE_EXCEEDED,
    /** 高氯硫燃料加权入窑硫总量超过限值。 */
    SULFUR_EXCEEDED,
    /** 未绑定危废经营许可或许可已过期。 */
    LICENSE_INVALID,
    /** 状态机不允许当前操作。 */
    ILLEGAL_STATE,
    /** 同一窑同时刻已有执行中方案。 */
    KILN_OCCUPIED,
    /** 当日已被连续3小时超标禁烧。 */
    KILN_BANNED,
    /** 同 requestId 指纹不一致。 */
    IDEMPOTENT_CONFLICT,
    /** 升温曲线未走完，禁止投料。 */
    CURVE_NOT_FINISHED,
    /** 阶段确认时实测温度未达目标温度。 */
    TARGET_NOT_REACHED,
    /** 同一窑同时刻已存在执行中升温票。 */
    WARMUP_TICKET_ACTIVE
}
