package com.example.starter.damage.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 修箱密封报价与供应商资格响应视图集合。
 * 开标清单、排除原因与复核记录均可复算，重复开标原样返回首次冻结结果。
 */
public final class SealedViews {

    private SealedViews() {
    }

    /** 询价残损项视图（固定快照） */
    public record InquiryItemView(
            int itemSeq,
            long caseItemId,
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
            LocalDateTime openedAt,
            Long approvalId,
            List<InquiryItemView> items) {
    }

    /** 报价分项视图 */
    public record BidLineView(
            int itemSeq,
            String methodCode,
            long priceCents) {
    }

    /** 报价版本视图（含历史修订时由列表给出） */
    public record BidView(
            long id,
            long inquiryId,
            String supplierNo,
            int revision,
            long totalCents,
            String status,
            String result,
            String exclusionReason,
            Long qualificationId,
            List<BidLineView> lines) {
    }

    /** 开标清单行：含中标/落标/排除及原因，按总价、供应商编号排序 */
    public record OpeningBidView(
            long bidId,
            String supplierNo,
            int revision,
            long totalCents,
            String result,
            String exclusionReason) {
    }

    /**
     * 开标结果视图（首次开标冻结，重复开标原样返回）。
     * status：SELECTED 已选定中标 / NO_VALID_BID 零有效报价。
     * converted：双人不同复核完成并已转原案修理批复。
     */
    public record OpeningView(
            long id,
            long inquiryId,
            int inquiryVersion,
            long caseId,
            String status,
            Long winnerBidId,
            String winnerSupplierNo,
            Long totalCents,
            Long approvalId,
            LocalDateTime openedAt,
            List<OpeningBidView> rankedBids,
            List<String> reviewers,
            boolean converted) {
    }

    /** 供应商资格版本视图 */
    public record QualificationView(
            long id,
            String supplierNo,
            int version,
            LocalDateTime validFrom,
            LocalDateTime validUntil,
            String status,
            LocalDateTime revokedAt,
            List<String> methods) {
    }

    /** 供应商视图（含当前最新资格版本，无则 null） */
    public record SupplierView(
            String supplierNo,
            String name,
            QualificationView currentQualification) {
    }

    /**
     * 撤销结果：REVOKED 资格已撤销冻结 / RISK_RECORDED 中标批复后撤销仅记风险事件。
     */
    public record RevokeResultView(
            String result,
            QualificationView qualification) {
    }
}
