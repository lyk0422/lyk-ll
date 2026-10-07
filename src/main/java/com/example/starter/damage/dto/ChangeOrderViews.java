package com.example.starter.damage.dto;

import com.example.starter.damage.domain.ChangeOrderStatus;

import java.time.Instant;
import java.util.List;

/**
 * 修理变更单响应视图集合。金额为正整数分，时刻均为 UTC（{@link Instant}）。
 * 每项展示前后工法与金额，并给出前后总额；原询价与报价不覆盖，均可溯源。
 */
public final class ChangeOrderViews {

    private ChangeOrderViews() {
    }

    /** 变更单全清单行视图：前后工法与分报价对照，未变更项前后一致 */
    public record ChangeOrderLineView(
            long damageItemId,
            String iiclCode,
            String component,
            boolean changed,
            String oldMethodCode,
            long oldAmountCents,
            String newMethodCode,
            long newAmountCents) {
    }

    /** 复核记录视图 */
    public record ChangeOrderReviewView(
            String reviewer,
            String decision,
            Instant createdAt) {
    }

    /**
     * 变更单视图。
     * version 为单据乐观锁版本（从 1 起）；newEstimateId/newApprovalId 仅生效后非空。
     */
    public record ChangeOrderView(
            long id,
            long caseId,
            int seq,
            int version,
            ChangeOrderStatus status,
            long approvalId,
            long estimateId,
            long openingId,
            long supplierId,
            String supplierNo,
            int qualificationVersion,
            long oldTotalCents,
            long newTotalCents,
            Long newEstimateId,
            Long newApprovalId,
            List<ChangeOrderLineView> lines,
            List<ChangeOrderReviewView> reviews,
            Instant createdAt,
            Instant updatedAt) {
    }
}
