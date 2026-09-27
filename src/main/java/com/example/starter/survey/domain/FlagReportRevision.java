package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 报告修订：每次导入/更正生成一版，逐项结论挂在修订上，历史修订不可改。
 *
 * @param id          主键
 * @param reportId    报告头主键
 * @param revision    修订号（报告内从 1 开始递增）
 * @param completedAt 检查完成时刻（UTC，精确时刻）
 * @param attestation 合成签认标记
 * @param requestId   产生本修订的写操作幂等键
 * @param fingerprint 业务指纹（requestId + 全部业务字段）
 * @param createdAt   创建时刻（UTC）
 */
public record FlagReportRevision(
        Long id,
        Long reportId,
        Integer revision,
        Instant completedAt,
        String attestation,
        String requestId,
        String fingerprint,
        Instant createdAt
) {
}
