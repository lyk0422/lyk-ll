package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 返修放行申请视图。
 *
 * @param applicationId    申请主键
 * @param requestId        幂等键
 * @param physicalId       合成物理辊唯一编号
 * @param oldRollNo        旧代辊号
 * @param oldGenerationNo  旧代代次号
 * @param newRollNo        拟用新辊号（成功放行后成为下一代辊号）
 * @param groundDiameterMm 磨后直径（毫米，两位小数）
 * @param newCycleLimitKm  新一代周期公里上限（公里）
 * @param inspectionCert   检验凭据
 * @param expectedVersion  提交时携带的聚合版本
 * @param aggregateVersion 当前物理辊聚合版本
 * @param status           申请状态：PENDING 待两人检验 / APPROVED 已放行建代 / REJECTED 已终止
 * @param approvals        已登记的合格检验员人数（满 2 放行）
 * @param successorRollNo  成功建立的下一代辊号；未放行为 null
 * @param createdAt        申请创建时刻（UTC）
 */
public record RepairApplyView(
        Long applicationId,
        String requestId,
        Long physicalId,
        String oldRollNo,
        Integer oldGenerationNo,
        String newRollNo,
        BigDecimal groundDiameterMm,
        BigDecimal newCycleLimitKm,
        String inspectionCert,
        Integer expectedVersion,
        Integer aggregateVersion,
        String status,
        Integer approvals,
        String successorRollNo,
        LocalDateTime createdAt) {
}
