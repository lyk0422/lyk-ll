package com.example.starter.ventilation;

/**
 * 火灾模式会话状态。
 */
public enum ModeStatus {
    /** 激活中：指令序列下发/到位阶段 */
    ACTIVE,
    /** 复位中：火警解除，按逆序复位 */
    RESETTING,
    /** 复位完成：复位指令全部到位，归档前不接受新的激活 */
    RESET_COMPLETE,
    /** 已归档：终态 */
    ARCHIVED
}
