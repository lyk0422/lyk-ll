package com.example.starter.radio.model;

import com.example.starter.radio.domain.DoseChangeStatus;

/**
 * 未发药分装给药时刻变更候选单。候选形成时不改有效分装；两名不同人员复核齐备后同事务生效。
 * candidateActivityBq 按原合成核素半衰期与原母液初始活度快照重算；
 * inventoryAvailableBq 为提交时库存校验证据；可空字段为 NULL 表示对应步骤尚未发生。
 */
public record DoseChangeOrder(
        long id,
        String requestId,
        String fingerprint,
        long dispensingId,
        int expectedVersion,
        long prescriptionId,
        long newAdministrationTimeEpoch,
        int candidateVersion,
        double candidateActivityBq,
        double candidateDeviationRatio,
        double motherInitialActivityBq,
        long motherReferenceTimeEpoch,
        long halfLifeSeconds,
        double inventoryAvailableBq,
        DoseChangeStatus status,
        String firstReviewer,
        String secondReviewer,
        long submittedAtEpoch,
        Long decidedAtEpoch) {
}
