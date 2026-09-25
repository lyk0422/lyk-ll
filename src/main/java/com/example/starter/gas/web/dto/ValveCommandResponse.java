package com.example.starter.gas.web.dto;

/**
 * 阀门指令响应。
 *
 * @param effective   是否经双人确认而生效；单人操作恒为 false 且阀门状态不变
 * @param alarmActive 指令处理后站内是否仍有未解除告警
 * @param reason      未生效原因等说明
 */
public record ValveCommandResponse(
        String requestId,
        String stationCode,
        String action,
        boolean effective,
        String valveState,
        boolean alarmActive,
        String reason,
        boolean idempotentReplayed
) {
}
