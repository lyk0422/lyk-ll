package com.example.starter.cutover.api;

import java.time.LocalDateTime;

/**
 * 回退判据命中记录视图，供审计复算。
 *
 * @param exists        是否存在回退记录
 * @param triggerReason 回退判据枚举名
 * @param detail        判据命中明细
 * @param stepSeq       触发回退的步骤序号，无关联步骤为 null
 * @param snapshotJson  回退时点步骤状态快照（JSON）
 * @param rolledBackAt  回退指令下达时间
 */
public record RollbackRecordView(
        boolean exists,
        String triggerReason,
        String detail,
        Integer stepSeq,
        String snapshotJson,
        LocalDateTime rolledBackAt
) {
}
