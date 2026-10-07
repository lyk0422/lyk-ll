package com.example.starter.damage.domain;

/**
 * 修理变更单候选状态：待复核 → 生效/撤回/驳回。
 * EFFECTIVE、WITHDRAWN、REJECTED 均为终态，生效后不可撤回。
 */
public enum ChangeOrderStatus {
    /** 待复核：阻断原完工入口，等待两名不同复核人 */
    PENDING,
    /** 已生效：已换版新估价与新批复，旧批复失效 */
    EFFECTIVE,
    /** 已撤回：解除完工阻断，保留原有效批复 */
    WITHDRAWN,
    /** 已驳回：解除完工阻断，保留原有效批复 */
    REJECTED
}
