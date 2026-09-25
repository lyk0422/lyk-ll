package com.example.starter.dispatch.api;

import com.example.starter.dispatch.domain.OrderStatus;

import java.time.Instant;
import java.util.List;

/**
 * 调度令视图（含逐门执行明细）。
 *
 * @param requestId       幂等键
 * @param status          当前状态
 * @param deadline        执行时限（UTC）
 * @param issuedAt        下达时刻（UTC）
 * @param acknowledgedAt  签收时刻（UTC），未签收为 null
 * @param finishedAt      终态时刻（UTC），未终态为 null
 * @param escalated       是否已超时升级
 * @param takenOver       超时升级后是否已被人工接管
 * @param gates           逐门执行明细，按令中顺序排列
 */
public record OrderView(
        String requestId,
        OrderStatus status,
        Instant deadline,
        Instant issuedAt,
        Instant acknowledgedAt,
        Instant finishedAt,
        boolean escalated,
        boolean takenOver,
        List<GateStepView> gates) {
}
