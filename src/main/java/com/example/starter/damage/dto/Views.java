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

    /** 资格版本视图；frozen=true 表示定标后已冻结 */
    public record QualificationView(
            long id,
            int version,
            List<String> methods,
            LocalDateTime validFrom,
            LocalDateTime validUntil,
            String status,
            boolean frozen) {
    }

    /** 供应商视图：含当前资格版本 */
    public record SupplierView(
            long id,
            String supplierNo,
            String name,
            int version,
            QualificationView qualification) {
    }

    /** 供应商全量明细：含全部资格版本历史与风险事件 */
    public record SupplierDetailView(
            long id,
            String supplierNo,
            String name,
            int version,
            List<QualificationView> qualifications,
            List<RiskEventView> riskEvents) {
    }

    /** 询价版本视图 */
    public record InquiryView(
            long id,
            long caseId,
            int version,
            LocalDateTime deadline,
            String status) {
    }

    /** 询价项视图：残损项 ID 与允许工法集合 */
    public record InquiryItemView(
            long id,
            long itemId,
            List<String> methods) {
    }

    /** 报价行视图；金额单位为正整数分 */
    public record QuotationLineView(
            long itemId,
            String method,
            long amountCents) {
    }

    /** 报价版本视图 */
    public record QuotationView(
            long id,
            long inquiryId,
            long supplierId,
            int version,
            long totalAmount,
            String status,
            boolean frozen,
            List<QuotationLineView> lines) {
    }

    /** 开标明细视图：资格失效报价列明排除原因 */
    public record BidOpeningEntryView(
            long quotationId,
            long supplierId,
            String supplierNo,
            long totalAmount,
            boolean included,
            String exclusionReason,
            Integer qualVersion,
            Integer rank) {
    }

    /** 开标结果视图；winnerQuotationId 为 null 表示零有效报价 */
    public record BidOpeningView(
            long id,
            long inquiryId,
            LocalDateTime openedAt,
            Long winnerQuotationId,
            List<BidOpeningEntryView> entries) {
    }

    /** 复核记录视图 */
    public record ReviewView(long id, String reviewer) {
    }

    /** 询价全量明细：询价版本 + 固定项 + 报价 + 开标结果 + 复核记录 */
    public record InquiryDetailView(
            InquiryView inquiry,
            List<InquiryItemView> items,
            List<QuotationView> quotations,
            BidOpeningView opening,
            List<ReviewView> reviews) {
    }

    /** 风险事件视图（如定标后撤销资格） */
    public record RiskEventView(
            long id,
            Long caseId,
            Long inquiryId,
            long supplierId,
            String eventType,
            String detail) {
    }
}
