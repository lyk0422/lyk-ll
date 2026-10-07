package com.example.starter.rolling.api.dto;

import java.time.LocalDateTime;

/**
 * 返修检验确认视图。
 *
 * @param applicationId    申请主键
 * @param requestId        本次检验的幂等键
 * @param physicalId       合成物理辊唯一编号
 * @param status           申请状态：PENDING 继续待检 / APPROVED 两人合格已原子建代 / REJECTED 不合格终止
 * @param inspectorId      本次检验员编号
 * @param approved         本次检验是否合格
 * @param approvals        当前累计合格检验员人数
 * @param successorRollNo  放行成功后建立的下一代辊号；否则为 null
 * @param aggregateVersion 放行成功后的聚合新版本；未放行返回当前版本
 * @param inspectedAt      检验确认时刻（UTC）
 */
public record RepairInspectionView(
        Long applicationId,
        String requestId,
        Long physicalId,
        String status,
        String inspectorId,
        boolean approved,
        Integer approvals,
        String successorRollNo,
        Integer aggregateVersion,
        LocalDateTime inspectedAt) {
}
