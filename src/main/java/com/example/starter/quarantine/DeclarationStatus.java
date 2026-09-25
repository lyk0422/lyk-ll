package com.example.starter.quarantine;

/**
 * 检疫申报状态。
 */
public enum DeclarationStatus {

    /** 已申报，等待出证。 */
    DECLARED,
    /** 已驳回（如申报时养殖场已处于疫点封锁区）。 */
    REJECTED,
    /** 已出证。 */
    CERTIFIED
}
