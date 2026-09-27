package com.example.starter.pesticide;

/**
 * 联检单状态机：OPEN 已创建待分样 → ALLOCATED 已分样待判定 → DECIDED 已综合判定（终态）。
 */
public enum JointStatus {
    /** 已创建，待分样 */
    OPEN,
    /** 已分样，待逐项结果与综合判定 */
    ALLOCATED,
    /** 已综合判定（终态），后续结果只记补充信息 */
    DECIDED
}
