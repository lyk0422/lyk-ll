package com.example.starter.radio.model;

import com.example.starter.radio.domain.ChangeStatus;

/**
 * 给药时刻变更单。候选期间有效分装不变；expectedVersion 必须等于当前版本；
 * 候选版本号为提交时当前版本+1；旧版本计算与签认永久保留。
 */
public record DispensingChange(
        long id,
        String requestId,
        String fingerprint,
        long dispensingId,
        long expectedVersion,
        long newAdministrationTimeEpoch,
        long candidateVersionNo,
        Double candidateActivityBq,
        Double candidateDeviationRatio,
        ChangeStatus status,
        String firstChecker,
        String secondChecker,
        Long approvedAtEpoch,
        Long voidedAtEpoch,
        long createdAtEpoch,
        long updatedAtEpoch) {
}
