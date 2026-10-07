package com.example.starter.radio.model;

/**
 * 撤回单双人批准链。approveSeq：1 第一人 / 2 第二人，两名人员必须不同（均为 UTC epoch 秒）。
 */
public record WithdrawalApproval(
        long id,
        long withdrawalId,
        String approver,
        int approveSeq,
        long approvedAtEpoch) {
}
