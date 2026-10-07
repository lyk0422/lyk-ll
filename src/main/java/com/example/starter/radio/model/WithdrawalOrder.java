package com.example.starter.radio.model;

import com.example.starter.radio.domain.WithdrawalStatus;

/**
 * 未发药整批撤回单。申请只创建待批准状态并冻结全清单，不立即作废。
 * 全部分装须引用同一母液、均为合法已计算但未发药（CALCULATED/CHECKED），且各携带当前有效计算版本与逐项原因。
 * 两名不同人员批准后在最终事务重查全清单：任一已发药或计算版本已换，全部不撤回；
 * 全合法才一起把分装转为 VOID 终态、结束全部未生效时刻候选并使核对不再有效。
 * 基线计算不扣母液库存，撤回不向母液余额加量，也不改已发药测量与对账账本。
 * 可空字段为 NULL 表示对应步骤尚未发生（均为 UTC epoch 秒）。
 * versionNo 为撤回单乐观版本：修改须携带 expectedVersion，成功加 1，旧版 409。
 */
public record WithdrawalOrder(
        long id,
        String requestId,
        String fingerprint,
        String contentFingerprint,
        long motherLiquorId,
        WithdrawalStatus status,
        String reason,
        String firstApprover,
        String secondApprover,
        Long approvedAtEpoch,
        String cancelRequestId,
        Long cancelledAtEpoch,
        int versionNo,
        long createdAtEpoch,
        long updatedAtEpoch) {
}
