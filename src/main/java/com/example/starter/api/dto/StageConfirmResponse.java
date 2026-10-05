package com.example.starter.api.dto;

import java.util.List;

/**
 * 升温阶段确认结果。
 *
 * @param ticketNo           票编号
 * @param stageNo            被确认阶段序号
 * @param stageType          被确认阶段类型 RAMP/HOLD
 * @param confirmStatus      确认结果：PENDING_SECOND 待第二人复核 / ADVANCED 已推进下一升温段 /
 *                           HOLD_ENTERED 恰满最长用时未达目标转入保温段 / COMPLETED 曲线走完
 * @param confirmedOperators 该阶段已登记确认人
 * @param ticketStatus       确认后票状态
 */
public record StageConfirmResponse(
        String ticketNo,
        int stageNo,
        String stageType,
        String confirmStatus,
        List<String> confirmedOperators,
        String ticketStatus) {
}
