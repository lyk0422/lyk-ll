package com.example.starter.flood.domain;

/**
 * 泄流指令链状态：下达→签收→执行→回执；超时未签收进入升级态。
 */
public enum OrderStatus {
    /** 已下达，等待签收。 */
    ISSUED,
    /** 已签收。 */
    SIGNED,
    /** 已执行（现场开闸），等待回执。 */
    EXECUTING,
    /** 已回执，指令链闭环。 */
    RECEIPTED,
    /** 恰满签收时限未签收，自动升级值班领导。 */
    ESCALATED
}
