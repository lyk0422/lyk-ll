package com.example.starter.runway;

/**
 * 施工段状态：PENDING未生效 / LOCKED闭锁中 / CLEARED已验收解除。
 */
public enum ConstructionSegmentStatus {
    /** 未生效：施工单尚未生效，不产生闭锁。 */
    PENDING,
    /** 闭锁中：施工单已生效，该段计入活动施工区间并集。 */
    LOCKED,
    /** 已验收解除：两个不同验收人凭据齐全，本段闭锁消除。 */
    CLEARED
}
