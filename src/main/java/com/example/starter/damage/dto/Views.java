package com.example.starter.damage.dto;

import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.Responsibility;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 残损检验服务响应视图集合：残损清单、估价版本与修理链均可复算。
 */
public final class Views {

    private Views() {
    }

    /**
     * 残损案概要。
     * closedAt 为结案时间（UTC），null 表示未结案。
     */
    public record CaseView(
            long id,
            String containerNo,
            BigDecimal containerValue,
            CaseStatus status,
            Determination determination,
            int reworkCount,
            LocalDateTime closedAt) {
    }

    /** 残损项视图 */
    public record DamageItemView(
            long id,
            String iiclCode,
            String component,
            String description,
            Responsibility responsibility,
            BigDecimal repairCost) {
    }

    /** 估价版本视图 */
    public record EstimateView(
            long id,
            int version,
            BigDecimal totalCost,
            Determination determination,
            String status) {
    }

    /** 修理批复视图 */
    public record ApprovalView(
            long id,
            long estimateId,
            LocalDate approvalDate,
            String status) {
    }

    /** 修理完工视图 */
    public record CompletionView(
            long id,
            LocalDate completionDate,
            boolean rework) {
    }

    /** 复检记录视图 */
    public record ReinspectionView(long id, boolean passed) {
    }

    /** 案件事件视图（含结案后迟到照片事件） */
    public record EventView(long id, String eventType, String payload) {
    }

    /** 残损案全量明细：案件 + 残损清单 + 估价版本 + 修理链 + 事件 */
    public record CaseDetailView(
            CaseView caseInfo,
            List<DamageItemView> items,
            List<EstimateView> estimates,
            List<ApprovalView> approvals,
            List<CompletionView> completions,
            List<ReinspectionView> reinspections,
            List<EventView> events) {
    }

    /** 检验照片登记结果：ATTACHED 正常归档 / LATE_RECORDED 结案后迟到仅记事件 */
    public record PhotoResult(String result) {
    }
}
