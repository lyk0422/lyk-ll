package com.example.starter.domain;

/**
 * 工序卡状态机。
 *
 * <p>正常流转：{@link #DISPATCHED} 派工 → {@link #TIGHTENED} 全部紧固点力矩合格
 * → {@link #INSPECTED} 自检互检完成 → {@link #WAITING_WITNESS} 见证缺席等待
 * → {@link #WITNESSED} H 点见证到场 → {@link #ARCHIVED} 放行归档（终态）。
 *
 * <p>{@link #ESCALATED} 等待满 2 小时升级；{@link #QUARANTINED} 返工 2 次后仍超差，整批隔离评审。
 */
public enum CardStatus {
    /** 已派工，力矩紧固进行中。 */
    DISPATCHED,
    /** 全部紧固点最近一次力矩测量合格。 */
    TIGHTENED,
    /** 自检、互检完成且签署人不同。 */
    INSPECTED,
    /** H 点见证缺席，转入等待。 */
    WAITING_WITNESS,
    /** H 点见证人已到场签署。 */
    WITNESSED,
    /** 见证等待满 2 小时（含恰满 2 小时）已升级。 */
    ESCALATED,
    /** 同一紧固点返工 2 次后仍超差，整批隔离评审。 */
    QUARANTINED,
    /** 已放行归档，终态。 */
    ARCHIVED
}
