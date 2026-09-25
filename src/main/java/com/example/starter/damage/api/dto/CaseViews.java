package com.example.starter.damage.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** 对外响应视图集合；金额单位元，时间字段为 UTC 毫秒。 */
public final class CaseViews {

    private CaseViews() {
    }

    /** 残损案视图。 */
    public record CaseResponse(
            String caseNo,
            String containerNo,
            BigDecimal containerValue,
            String status,
            String closeOutcome,
            Long approvedAtUtc,
            Long validUntilUtc,
            Integer repairAttempts,
            Long createdAtUtc,
            Long closedAtUtc,
            EstimateView currentEstimate,
            List<EstimateView> estimates,
            List<DamageItemView> items,
            List<RepairCycleView> repairCycles,
            List<EventView> events) {
    }

    /** 残损清单明细视图。 */
    public record DamageItemView(
            String code,
            String location,
            String responsibility,
            String repairCode,
            String repairName,
            BigDecimal unitCost,
            Integer quantity,
            BigDecimal amount) {
    }

    /** 估价版本视图。 */
    public record EstimateView(
            Long estimateId,
            Integer versionNo,
            BigDecimal subtotalOwner,
            BigDecimal subtotalTerminal,
            BigDecimal totalAmount,
            BigDecimal maxItemAmount,
            BigDecimal ratio,
            String decision) {
    }

    /** 修理链一环。 */
    public record RepairCycleView(
            Integer attemptNo,
            String status,
            Long approvedAtUtc,
            Long validUntilUtc,
            Long completedAtUtc,
            Long reinspectedAtUtc,
            String reinspectResult) {
    }

    /** 案件事件视图（含结案后迟到检验事件）。 */
    public record EventView(
            String eventType,
            String payload,
            Long createdAtUtc) {
    }

    /** 登记结果视图。 */
    public record RegisterResponse(
            String caseNo,
            String containerNo,
            String status,
            Long createdAtUtc) {
    }
}
