package com.example.starter.domain;

/**
 * 放行拒绝原因。
 */
public enum RejectReason {

    /** 单位不处于可放行状态（待检/隔离中）。 */
    NOT_RELEASABLE,
    /** 该单位已被其他放行请求放行发血。 */
    UNIT_RELEASED,
    /** 单位已报废（检测不合格或人工报废）。 */
    SCRAPPED,
    /** 单位已超过效期边界，被判定为过期报废。 */
    EXPIRED,
    /** 请求时刻不在声明的放行时间窗内。 */
    OUTSIDE_WINDOW
}
