package com.example.starter.radio.model;

import com.example.starter.radio.domain.ChangeOrderStatus;

/**
 * 给药时刻变更候选单。形成候选不立即改有效分装；双人复核通过后在同一事务内生效。
 * candidateActivityBq 为按原合成核素半衰期与原母液快照重算至新给药时刻的目标活度（Bq）；
 * 超偏差或库存校验失败时为 NULL，calcStatus=REJECTED，且不扣减母液。
 * 可空时间戳为 NULL 表示对应步骤尚未发生（均为 UTC epoch 秒）。
 */
public record ChangeOrder(
        long id,
        String requestId,
        String fingerprint,
        long dispensingId,
        int expectedVersion,
        int prescriptionVersion,
        Long newPrescriptionId,
        long newAdministrationTimeEpoch,
        int candidateVersionNo,
        Double candidateActivityBq,
        Double candidateDeviationRatio,
        String calcStatus,
        ChangeOrderStatus status,
        String firstReviewer,
        String secondReviewer,
        Long approvedAtEpoch,
        Long voidedAtEpoch,
        String voidRequestId,
        long createdAtEpoch,
        long updatedAtEpoch) {
}
