package com.example.starter.runway;

/**
 * 施工单状态机。
 *
 * <p>流转：PENDING（未生效）→ ACTIVE（已生效闭锁）→ COMPLETED（全部段验收完成，终态）；
 * PENDING 可撤回为 WITHDRAWN（终态）。已生效不得直接删除或缩段。</p>
 */
public enum ConstructionOrderStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    WITHDRAWN;

    /** 是否终态：终态单据不再变更。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == WITHDRAWN;
    }
}
