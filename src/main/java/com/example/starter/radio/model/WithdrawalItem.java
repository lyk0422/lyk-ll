package com.example.starter.radio.model;

/**
 * 撤回单冻结清单项（逐项）。frozenVersionNo 为创建时冻结的分装当前有效计算版本号；
 * 最终事务若发现该分装已发药或版本号已换，则整单全部不撤回。
 * reason 为该项撤回原因，创建时快照，不再随后续修改变化。
 */
public record WithdrawalItem(
        long id,
        long withdrawalId,
        long dispensingId,
        int frozenVersionNo,
        String reason,
        long createdAtEpoch) {
}
