package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 返修放行申请视图。
 *
 * @param id                    申请 id
 * @param requestId             幂等键
 * @param physicalNo            返修对象物理辊编号
 * @param oldRollNo             旧代次辊号
 * @param oldGenerationNo       旧代次号
 * @param newRollNo             拟用新辊号（放行成功后成为新代辊号）
 * @param diameterAfterGrindMm  磨后直径（毫米，两位小数）
 * @param cycleLimitKm          新代周期上限（公里）
 * @param inspectionCertNo      检验凭据编号
 * @param mountedAt             新辊拟上机时刻（UTC）
 * @param status                INSPECTING / RELEASED / TERMINATED
 * @param firstInspector        第一名检验员（未确认为 null）
 * @param firstQualified        第一名是否合格（未确认为 null）
 * @param firstConfirmedAt      第一名确认时刻（UTC）
 * @param secondInspector       第二名检验员（未确认为 null）
 * @param secondQualified       第二名是否合格（未确认为 null）
 * @param secondConfirmedAt     第二名确认时刻（UTC）
 * @param releasedAt            放行成功时刻（UTC）；未放行 null
 * @param terminatedAt          终止时刻（UTC）；未终止 null
 * @param newGenerationNo       放行成功后建立的新代次号；未放行 null
 * @param version               申请聚合乐观版本（从 1 起）
 */
public record ReworkApplicationView(
        Long id,
        String requestId,
        String physicalNo,
        String oldRollNo,
        Integer oldGenerationNo,
        String newRollNo,
        BigDecimal diameterAfterGrindMm,
        BigDecimal cycleLimitKm,
        String inspectionCertNo,
        LocalDateTime mountedAt,
        String status,
        String firstInspector,
        Boolean firstQualified,
        LocalDateTime firstConfirmedAt,
        String secondInspector,
        Boolean secondQualified,
        LocalDateTime secondConfirmedAt,
        LocalDateTime releasedAt,
        LocalDateTime terminatedAt,
        Integer newGenerationNo,
        Integer version) {
}
