package com.example.starter.damage.domain;

/**
 * 修理变更单候选状态。
 * PENDING → EFFECTIVE（双人复核同意切换）/ WITHDRAWN（撤回）/ REJECTED（驳回）。
 */
public enum ChangeOrderStatus {
    /** 待复核：阻断原完工入口，每案最多一个 */
    PENDING,
    /** 已生效：新估价与批复已切换，不可撤回 */
    EFFECTIVE,
    /** 已撤回：解除完工阻断，保留原有效批复 */
    WITHDRAWN,
    /** 已驳回：解除完工阻断，保留原有效批复 */
    REJECTED
}
