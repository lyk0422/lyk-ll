package com.example.starter.web.dto;

/**
 * 解除限功率响应。
 *
 * @param directiveId    指令主键
 * @param retestId       同事务落库的复测记录主键
 * @param status         指令状态（LIFTED）
 * @param restoredPowerW 解除后恢复的额定满功率，单位 W
 */
public record LiftResponse(long directiveId, long retestId, String status, int restoredPowerW) {
}
