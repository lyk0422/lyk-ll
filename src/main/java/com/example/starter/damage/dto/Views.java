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

    // ==================== 密封询价与比价流程视图 ====================

    /** 供应商视图（含版本、状态、适用工法与当前资格版本） */
    public record SupplierView(
            long id,
            String supplierCode,
            String name,
            int version,
            String status,
            List<String> methods,
            QualificationView currentQualification) {
    }

    /** 资格版本视图；revokedAt 非空表示已撤销 */
    public record QualificationView(
            int version,
            LocalDateTime validFrom,
            LocalDateTime validTo,
            String status,
            LocalDateTime revokedAt,
            String revokeReason) {
    }

    /** 询价残损项视图（固定集合与允许工法） */
    public record InquiryItemView(
            long id,
            int seqNo,
            long damageItemId,
            String iiclCode,
            String component,
            List<String> allowedMethods) {
    }

    /** 询价版本视图 */
    public record InquiryView(
            long id,
            long caseId,
            int version,
            LocalDateTime deadline,
            String status,
            List<InquiryItemView> items) {
    }

    /** 报价逐项视图 */
    public record BidItemView(
            long inquiryItemId,
            int seqNo,
            String methodCode,
            long amountCents) {
    }

    /** 报价版本视图（含修订号与冻结状态/排除原因） */
    public record BidView(
            long id,
            long inquiryId,
            String supplierCode,
            int revision,
            long totalAmountCents,
            String status,
            String excludeReason,
            List<BidItemView> items) {
    }

    /** 开标清单单行：报价冻结留痕，排除项带原因，有效项带排名 */
    public record OpeningLineView(
            long bidId,
            String supplierCode,
            int revision,
            long totalAmountCents,
            boolean qualified,
            Integer qualificationVersion,
            String excludeReason,
            Integer rankNo) {
    }

    /** 开标结果视图：重复开标返回首次清单，不因后续资格变化重算 */
    public record OpeningView(
            long id,
            long inquiryId,
            LocalDateTime openedAt,
            String winnerSupplierCode,
            Long winnerBidId,
            Integer winnerRevision,
            Long winnerTotalAmountCents,
            List<OpeningLineView> lines) {
    }

    /** 双人复核转批复结果视图：含原案修理批复 ID 与冻结的资格版本快照 */
    public record AwardView(
            long id,
            long caseId,
            long inquiryId,
            long openingId,
            long winnerBidId,
            String supplierCode,
            int qualificationVersion,
            LocalDateTime qualValidFrom,
            LocalDateTime qualValidTo,
            String reviewer1,
            String reviewer2,
            long approvalId,
            LocalDate approvalDate) {
    }
}
