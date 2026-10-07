package com.example.starter.radio.domain;

/**
 * 分装批次状态机：CALCULATED 待核对 → CHECKED 双人核对完成 → DISPENSED 发药完成（终态）；
 * 分装计算偏差超限直接进入 REJECTED（终态）。
 * 未发药整批撤回双人批准后，一批“已计算但未发药”分装在同一事务内一起转为 VOID（终态）：
 * 原计算与签认证据保留，VOID 不得重新激活，之后新发药、改时刻或重新核对一律拒绝。
 */
public enum DispensingStatus {

    /** 分装计算完成，待双人核对。 */
    CALCULATED,

    /** 两名不同人员先后核对完成，可发药。 */
    CHECKED,

    /** 发药完成，终态；重复完成返回幂等成功。 */
    DISPENSED,

    /** 分装活度与处方偏差超过 ±10%，已拒绝，终态。 */
    REJECTED,

    /** 未发药整批撤回批准后同批转入，终态；原计算、版本与签认证据保留但不再有效，不得重新激活。 */
    VOID
}
