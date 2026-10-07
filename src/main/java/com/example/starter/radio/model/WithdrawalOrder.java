package com.example.starter.radio.model;

import com.example.starter.radio.domain.WithdrawalStatus;

/**
 * 未发药整批撤回单。申请引用同一母液的 1~10 份不同分装，冻结其当前有效计算版本与撤回原因；
 * 申请只建立 PENDING，不作废任何分装。两名不同人员批准后在最终事务重查全清单：
 * 任一已发药或计算版本已换则全部不撤回；全合法才同事务把各分装转为 VOID 终态。
 * 乐观版本：新撤回单 versionNo 从 1 起，修改须携带 expectedVersion，成功加 1，旧版 409。
 * 可空时间戳为 NULL 表示对应步骤尚未发生（均为 UTC epoch 秒）。
 */
public record WithdrawalOrder(
        long id,
        String requestId,
        String fingerprint,
        long motherLiquorId,
        String reason,
        WithdrawalStatus status,
        int versionNo,
        String firstApprover,
        String secondApprover,
        Long approvedAtEpoch,
        String cancelRequestId,
        Long cancelledAtEpoch,
        long createdAtEpoch,
        long updatedAtEpoch) {
}
