package com.example.starter.ventilation;

/**
 * 设备指令执行状态。
 */
public enum CommandStatus {
    /** 待下发（前序设备未到位） */
    PENDING,
    /** 已下发，等待到位反馈 */
    SENT,
    /** 已到位 */
    ACKED,
    /** 两次下发均超时，判设备故障 */
    FAULT,
    /** 共享风阀与先激活分区目标状态冲突，本会话不驱动该风阀 */
    CONFLICT,
    /** 共享风阀已被先激活分区调整为相同目标状态，无需重复下发 */
    ALIGNED
}
