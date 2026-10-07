package com.example.starter.radio.model;

/**
 * 撤回单双人批准链（不可变证据），按批准次序留存：两名批准人员必须不同。
 */
public record WithdrawalApproval(
        long id,
        long withdrawalOrderId,
        String approver,
        int approvalSeq,
        long approvedAtEpoch) {
}
