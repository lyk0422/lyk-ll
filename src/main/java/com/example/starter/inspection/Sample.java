package com.example.starter.inspection;

import java.time.LocalDateTime;

import com.example.starter.domain.SamplingMode;

/**
 * 抽样记录，按（批次＋项目）唯一去重。时间均为 UTC。
 */
public record Sample(
        long id,
        String requestId,
        String fingerprint,
        long batchId,
        String item,
        SamplingMode mode,
        LocalDateTime createdAt) {
}
