package com.example.starter.dao;

import java.time.Instant;

/**
 * 潜水员反复潜水余量积分账户行（虚构模型，仅供软件验证）。
 *
 * @param diverCode             潜水员工号
 * @param lastSurfacedAtUtc     最近一次受控潜次确认出水时刻（UTC）
 * @param residualPoints        该次出水残留积分（消减前存储值；当前积分按水面时长推导）
 * @param sourceDiveRequestId   产生该积分的潜次 requestId
 * @param sourcePlanVersion     产生该积分的受控方案版本
 * @param version               账户版本，首次出水为 1，每次成功出水原子加 1
 */
public record ResidualAccountRow(
        String diverCode,
        Instant lastSurfacedAtUtc,
        int residualPoints,
        String sourceDiveRequestId,
        int sourcePlanVersion,
        int version,
        Instant createdAtUtc,
        Instant updatedAtUtc) {
}
