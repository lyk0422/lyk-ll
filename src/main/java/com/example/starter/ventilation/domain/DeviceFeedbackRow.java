package com.example.starter.ventilation.domain;

/**
 * 设备到位反馈流水行，对应 device_feedback 表。
 *
 * @param id          主键
 * @param requestId   反馈请求 id（可为空：超时/系统内部路径）
 * @param sessionId   命中的会话 id（归档后迟到反馈可能为空）
 * @param commandId   命中的指令 id（可为空）
 * @param zoneCode    分区编码（可为空）
 * @param deviceCode  设备编码
 * @param result      反馈结果
 * @param late        是否归档后迟到
 * @param receivedAt  接收时间 epoch 毫秒
 */
public record DeviceFeedbackRow(long id, String requestId, Long sessionId, Long commandId,
                                String zoneCode, String deviceCode, String result,
                                boolean late, long receivedAt) {
}
