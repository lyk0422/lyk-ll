package com.example.starter.damage.dto;

import com.example.starter.damage.domain.InquiryStatus;
import com.example.starter.damage.domain.QualificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 密封询价流程响应视图集合。时刻均为 UTC（{@link Instant}），金额为正整数分。
 */
public final class InquiryViews {

    private InquiryViews() {
    }

    /** 供应商视图：编号、适用工法与当前最新资格版本 */
    public record SupplierView(
            long id,
            String supplierNo,
            String name,
            List<String> methods,
            QualificationView currentQualification,
            int version) {
    }

    /** 资格版本视图；validTo 为 null 表示长期有效 */
    public record QualificationView(
            long id,
            int version,
            QualificationStatus status,
            Instant validFrom,
            Instant validTo) {
    }

    /** 询价残损项行视图：固定残损项与允许工法 */
    public record InquiryItemView(
            int lineNo,
            long damageItemId,
            String iiclCode,
            String component,
            List<String> allowedMethods) {
    }

    /** 询价版本视图；version 为乐观锁版本（从 1 起，成功写变更 +1） */
    public record InquiryView(
            long id,
            long caseId,
            int seq,
            int version,
            Instant deadline,
            InquiryStatus status,
            List<InquiryItemView> items) {
    }

    /** 报价明细行视图 */
    public record BidLineView(
            int lineNo,
            String methodCode,
            long amountCents) {
    }

    /** 报价版本视图 */
    public record BidVersionView(
            long id,
            int version,
            long totalCents,
            String status,
            List<BidLineView> lines) {
    }

    /** 某供应商对某询价的当前报价视图 */
    public record BidView(
            long id,
            long inquiryId,
            long supplierId,
            int currentVersion,
            String status,
            List<BidVersionView> history) {
    }

    /** 开标清单单行：含资格快照、有效性、排除原因与排名 */
    public record OpeningEntryView(
            long supplierId,
            String supplierNo,
            long bidVersionId,
            int bidVersion,
            long totalCents,
            int qualificationVersion,
            QualificationStatus qualificationStatus,
            Instant qualValidFrom,
            Instant qualValidTo,
            boolean valid,
            String excludeReason,
            Integer rankNo) {
    }

    /** 开标冻结清单视图；重复开标始终返回首次结果 */
    public record OpeningView(
            long id,
            long inquiryId,
            int inquiryVersion,
            Instant openedAt,
            String status,
            Long winnerSupplierId,
            String winnerSupplierNo,
            Long winnerBidVersionId,
            Long winnerTotalCents,
            List<String> reviewers,
            boolean converted,
            Long approvalId,
            List<OpeningEntryView> entries) {
    }

    /** 双重复核动作结果：返回最新开标清单；approvalDate 为转批复 UTC 日期 */
    public record ReviewResultView(
            OpeningView opening,
            LocalDate approvalDate) {
    }
}
