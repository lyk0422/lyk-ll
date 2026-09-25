package com.example.starter.radio.domain;

/**
 * 分装批次状态机：CALCULATED 待核对 → CHECKED 双人核对完成 → DISPENSED 发药完成（终态）；
 * 分装计算偏差超限直接进入 REJECTED（终态）。
 */
public enum DispensingStatus {

    /** 分装计算完成，待双人核对。 */
    CALCULATED,

    /** 两名不同人员先后核对完成，可发药。 */
    CHECKED,

    /** 发药完成，终态；重复完成返回幂等成功。 */
    DISPENSED,

    /** 分装活度与处方偏差超过 ±10%，已拒绝，终态。 */
    REJECTED
}
