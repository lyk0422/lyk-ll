package com.example.starter.cutover;

/**
 * 割接批次状态机：
 * PUBLISHED 已发布（未结束，成员/依赖/窗口快照已冻结）
 * -> CLOSED 已结束（全部成员到达终态，释放“一项申请最多属于一个未结束批次”约束）。
 */
public enum BatchStatus {
    PUBLISHED,
    CLOSED
}
