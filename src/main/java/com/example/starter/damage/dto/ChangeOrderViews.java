package com.example.starter.damage.dto;

import com.example.starter.damage.domain.ChangeDecision;
import com.example.starter.damage.domain.ChangeOrderStatus;

import java.time.Instant;
import java.util.List;

/**
 * 修理变更单响应视图集合。时刻均为 UTC（{@link Instant}），金额为正整数分。
 */
public final class ChangeOrderViews {

    private ChangeOrderViews() {
    }

    /** 变更单全清单快照行：每项前后工法与金额，未改项前后一致 */
    public record ChangeOrderLineView(
            long damageItemId,
            int lineNo,
            boolean changed,
            String beforeMethod,
            long beforeAmountCents,
            String afterMethod,
            long afterAmountCents) {
    }

    /** 复核记录视图 */
    public record ChangeReviewView(
            String reviewer,
            ChangeDecision decision) {
    }

    /**
     * 修理变更单视图：冻结原批复/资格版本快照，展示每项前后金额与工法及总额。
     * newEstimateId/newApprovalId 仅生效后非空；decidedAt 仅终态非空。
     */
    public record ChangeOrderView(
            long id,
            long caseId,
            int version,
            ChangeOrderStatus status,
            long approvalId,
            Long openingId,
            long supplierId,
            String supplierNo,
            int qualificationVersion,
            long originalTotalCents,
            long newTotalCents,
            Long newEstimateId,
            Long newApprovalId,
            List<ChangeOrderLineView> lines,
            List<ChangeReviewView> reviews,
            Instant createdAt,
            Instant decidedAt) {
    }
}
