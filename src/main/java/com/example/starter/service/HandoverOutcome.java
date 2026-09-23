package com.example.starter.service;

import com.example.starter.domain.SegmentStatus;

/**
 * 交接处理结果。
 *
 * @param closedVersion  被关闭段的 version
 * @param status         冻结的判定状态
 * @param currentVersion 处理后货件当前版本
 * @param receiver       新段接收方
 * @param handoverAt     交接时间
 * @param lastSequence   交接覆盖末序号
 * @param replayed       是否为同 requestId 同参重放
 */
public record HandoverOutcome(
        int closedVersion,
        SegmentStatus status,
        int currentVersion,
        String receiver,
        long handoverAt,
        long lastSequence,
        boolean replayed
) {
}
