package com.example.starter.crane.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 授权申请首次结论/幂等回放视图。
 *
 * @param requestId    幂等键
 * @param result       GRANTED 批准 / REJECTED_OVERLOAD 超载拒绝
 * @param status       GRANTED 有效 / RELEASED 已释放（拒绝时为 REJECTED）
 * @param craneCode    塔机编号
 * @param zoneCode     重叠区编号
 * @param startTime    作业开始时刻，UTC
 * @param endTime      作业结束时刻，UTC
 * @param loadKg       吊重，kg
 * @param rejectReason 拒绝原因，可空
 * @param replayed     true 表示本次为同键同参的幂等回放，非首次结论
 */
public record AuthorizationView(
        String requestId,
        String result,
        String status,
        String craneCode,
        String zoneCode,
        Instant startTime,
        Instant endTime,
        BigDecimal loadKg,
        String rejectReason,
        boolean replayed) {
}
