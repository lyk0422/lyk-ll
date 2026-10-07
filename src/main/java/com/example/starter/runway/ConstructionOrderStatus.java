package com.example.starter.runway;

/**
 * 施工单状态：DRAFT未生效 / EFFECTIVE已生效 / COMPLETED已结束 / WITHDRAWN已撤回（终态）。
 */
public enum ConstructionOrderStatus {
    /** 未生效：可修改段、可撤回；不产生闭锁。 */
    DRAFT,
    /** 已生效：冻结区间并阻断整跑道新使用；不得删除或缩段。 */
    EFFECTIVE,
    /** 已结束：全部施工段验收完成（终态）。 */
    COMPLETED,
    /** 已撤回：仅未生效单可撤回（终态）。 */
    WITHDRAWN;

    /** 是否终态（结束或撤回，不再变更）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == WITHDRAWN;
    }
}
