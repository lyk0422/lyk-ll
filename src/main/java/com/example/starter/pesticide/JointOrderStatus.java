package com.example.starter.pesticide;

/**
 * 联检单状态机：CREATED 已建单 → ASSIGNED 已全项分样 → PENDING 结果待定 → JUDGED 已综合判定（终态）。
 */
public enum JointOrderStatus {
    /** 已建单，待分样 */
    CREATED,
    /** 已全部项目分样，待结果 */
    ASSIGNED,
    /** 已有部分结果，尚有项目待定 */
    PENDING,
    /** 已综合判定（终态），此后结果只记补充信息 */
    JUDGED;
}
