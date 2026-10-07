package com.example.starter.radio.model;

/**
 * 撤回单冻结清单项（不可变证据）：创建撤回单时逐项冻结分装当时的有效计算版本号与撤回原因。
 * 最终批准重查时以 frozenVersionNo 比对分装当前版本，版本已换则整批不撤回。
 * terminatedCandidateId 为批准时被终结的该分装挂起时刻候选单主键；NULL 表示该分装当时无挂起候选。
 */
public record WithdrawalItem(
        long id,
        long withdrawalOrderId,
        long dispensingId,
        int frozenVersionNo,
        String reason,
        Long terminatedCandidateId,
        long createdAtEpoch) {
}
