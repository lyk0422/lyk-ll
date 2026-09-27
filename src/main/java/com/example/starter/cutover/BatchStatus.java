package com.example.starter.cutover;

/**
 * 割接批次状态：
 * OPEN 未结束（仍有成员未到达终态，成员占用“一个未结束批次”名额）；
 * CLOSED 已结束（全部成员到达终态，释放成员以便重新组批）。
 */
public enum BatchStatus {
    OPEN,
    CLOSED
}
