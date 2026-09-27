package com.example.starter.domain;

/**
 * 屠宰批次状态：按 申报→宰前检疫→同步检验→判定分流→出厂核销 流转，RELEASED 为终态。
 */
public enum BatchStatus {
    /** 已申报，等待宰前检疫。 */
    DECLARED,
    /** 宰前检疫通过，可开展同步检验。 */
    PRE_QUARANTINED,
    /** 同步检验进行中（已有头只完成检验）。 */
    INSPECTING,
    /** 存在漏检头只，整批暂停流转。 */
    PAUSED,
    /** 判定分流完成，等待回执勾稽与出厂核销。 */
    JUDGED,
    /** 风险抽检任一阳性：专项隔离，四类判定保留但受控出厂拒绝。 */
    SPECIAL_QUARANTINE,
    /** 出厂核销，终态。 */
    RELEASED
}
