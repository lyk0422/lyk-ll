package com.example.starter.audit.api;

import java.util.List;

/**
 * 检查点恢复结果。
 *
 * @param recovered      是否成功恢复
 * @param resumedFrom    恢复所基于的最近成功检查点阶段；无检查点为空
 * @param replayedStages 本次实际重跑的阶段
 * @param task           恢复后的任务详情
 * @param reasons        不可恢复原因（不可恢复时非空）
 */
public record RecoveryResult(
        boolean recovered,
        String resumedFrom,
        List<String> replayedStages,
        TaskView task,
        List<String> reasons
) {
}
