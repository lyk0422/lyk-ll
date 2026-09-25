package com.example.starter.ventilation;

/**
 * 设备指令执行状态。
 */
public enum CommandStatus {
    /** 已生成但尚未下发（等待前序设备到位） */
    PENDING,
    /** 已下发，等待到位反馈 */
    ISSUED,
    /** 已收到到位确认 */
    IN_POSITION,
    /** 重发后仍超时：设备故障 */
    FAILED,
    /** 因共享风阀冲突等原因跳过，不下发 */
    SKIPPED
}
