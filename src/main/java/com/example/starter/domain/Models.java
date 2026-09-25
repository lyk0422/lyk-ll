package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 领域模型与状态枚举。金额单位为元，时间均为 UTC 口径。
 */
public final class Models {

    private Models() {
    }

    /** 赔案状态：OPEN=未决，CLOSED=最终结案（终态）。 */
    public enum ClaimStatus {
        OPEN, CLOSED
    }

    /** 账单状态：PENDING=待确认，DISPUTED=已异议，SEALED=双方确认已封存（终态）。 */
    public enum BillStatus {
        PENDING, DISPUTED, SEALED
    }

    /** 账单类型：REGULAR=常规账单，ADJUSTMENT=差错调整单。 */
    public enum BillType {
        REGULAR, ADJUSTMENT
    }

    /** 账单确认方：CEDANT=分出公司，REINSURER=再保人。 */
    public enum Party {
        CEDANT, REINSURER
    }

    /** 摊回账户分录类型。 */
    public enum EntryType {
        PROVISION, PROVISION_ADJUST, REVERSAL
    }

    /** 比例再保合约。 */
    public record Treaty(long id, String treatyNo, String name, BigDecimal cessionRate,
                         BigDecimal limitPerPolicy, OffsetDateTime createdAt) {
    }

    /** 分出保单。 */
    public record Policy(long id, String policyNo, long treatyId, BigDecimal cededPremium,
                         OffsetDateTime createdAt) {
    }

    /** 赔案。 */
    public record Claim(long id, String claimNo, long policyId, BigDecimal estimateAmount,
                        BigDecimal paidAmount, ClaimStatus status, OffsetDateTime closedAt,
                        OffsetDateTime createdAt) {
    }

    /** 摊回账户。 */
    public record RecoveryAccount(long treatyId, BigDecimal balance, OffsetDateTime updatedAt) {
    }

    /** 摊回账户分录。 */
    public record AccountEntry(long id, long treatyId, long claimId, EntryType entryType,
                               BigDecimal amount, BigDecimal balanceAfter, String requestId,
                               OffsetDateTime createdAt) {
    }

    /** 账单头。 */
    public record Bill(long id, String billNo, long treatyId, String quarterLabel, BillType billType,
                       String originalBillNo, BillStatus status, BigDecimal totalPremiumCession,
                       BigDecimal totalClaimRecovery, BigDecimal totalAmount,
                       boolean confirmedByCedant, boolean confirmedByReinsurer, String reason,
                       OffsetDateTime createdAt, OffsetDateTime sealedAt) {
    }

    /** 账单明细（按保单一行）。 */
    public record BillItem(long id, long billId, long policyId, BigDecimal premiumCession,
                           BigDecimal claimRecovery, BigDecimal itemTotal, BigDecimal retainedAmount,
                           boolean limitApplied) {
    }

    /** 账单及其明细的视图，用于出账与逐笔复算。 */
    public record BillView(Bill bill, List<BillItem> items) {
    }

    /** 结案结果：重复结案返回首次结果并标记 replayed。 */
    public record CloseResult(String claimNo, BigDecimal paidAmount, BigDecimal provisionAmount,
                              BigDecimal reversalAmount, BigDecimal balanceAfter,
                              OffsetDateTime closedAt, boolean replayed) {
    }

    /** 估损调整结果：结案后迟到调整只记事件。 */
    public record EstimateAdjustResult(String claimNo, boolean recordedOnly, BigDecimal balanceAfter) {
    }
}
