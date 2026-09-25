package com.example.starter.dispatch.api.dto;

import com.example.starter.dispatch.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 调度令视图：下达/查询的返回体，重复查询结果稳定。
 *
 * @param requestId       幂等键
 * @param status          令状态
 * @param gates           闸门执行序列（按令中顺序）
 * @param alarms          本令产生的告警（偏差告警、超时升级通知），按发生顺序
 * @param deadline        执行时限（UTC）
 * @param issuedAt        下达时刻（UTC）
 * @param acceptedAt      签收时刻（UTC），空为未签收
 * @param completedAt     全部完成时刻（UTC），空为未完成
 * @param timeoutAt       超时升级时刻（UTC），空为未超时
 * @param cancelledAt     撤销时刻（UTC）
 * @param abortedAt       中止时刻（UTC）
 * @param escalated       是否已通知上级
 * @param abortedDetail   中止时已执行闸门实际开度留痕（JSON）
 */
public record OrderView(
        String requestId,
        OrderStatus status,
        List<GateReceiptView> gates,
        List<AlarmView> alarms,
        Instant deadline,
        Instant issuedAt,
        Instant acceptedAt,
        Instant completedAt,
        Instant timeoutAt,
        Instant cancelledAt,
        Instant abortedAt,
        boolean escalated,
        String abortedDetail) {

    /**
     * 单门执行情况。
     *
     * @param seqNo         令中顺序，从 0 开始
     * @param gateCode      闸门编码
     * @param targetOpening 目标开度（%）
     * @param actualOpening 实际开度（%），空为尚未回执
     * @param inPosition    是否到位（偏差在 ±2% 内，含边界）；空为尚未回执
     * @param deviationPct  偏差百分比=(实际-目标)/目标*100；空为尚未回执
     * @param alarmed       是否已偏差告警
     * @param receivedAt    回执到达时刻（UTC）
     */
    public record GateReceiptView(
            int seqNo,
            String gateCode,
            BigDecimal targetOpening,
            BigDecimal actualOpening,
            Boolean inPosition,
            BigDecimal deviationPct,
            boolean alarmed,
            Instant receivedAt) {
    }

    /**
     * 告警视图。
     *
     * @param type      GATE_DEVIATION 闸门执行偏差 / TIMEOUT_ESCALATION 超时升级
     * @param gateCode  偏差闸门编码；超时升级为空
     * @param detail    告警内容
     * @param createdAt 告警时刻（UTC）
     */
    public record AlarmView(
            String type,
            String gateCode,
            String detail,
            Instant createdAt) {
    }
}
