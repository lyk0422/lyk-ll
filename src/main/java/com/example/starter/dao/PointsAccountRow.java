package com.example.starter.dao;

import java.time.Instant;

/**
 * 潜水员反复潜水积分账行（虚构积分模型，不用于实际减压）。
 *
 * @param points           锚点出水时刻的残留积分；当前积分须按水面停留消减复算
 * @param anchorSurfacedAt 最近已确认出水时刻；空值表示尚无受控出水（积分按 0）
 * @param lastDiveRequestId 产生当前积分的最近受控潜次 requestId（旧方案消费防护来源标记）
 */
public record PointsAccountRow(
        Long id,
        String diverCode,
        int points,
        Instant anchorSurfacedAt,
        String lastDiveRequestId,
        Instant updatedAtUtc) {
}
