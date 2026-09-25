package com.example.starter.inspection;

import java.time.LocalDateTime;

import com.example.starter.domain.SamplingMode;

/**
 * 抽样响应。mode 为 NORMAL 常规 / ENHANCED 加严（产地预警期间）。时间均为 UTC。
 */
public record SampleResponse(
        long id,
        long batchId,
        String item,
        SamplingMode mode,
        LocalDateTime createdAt) {

    public static SampleResponse from(Sample sample) {
        return new SampleResponse(sample.id(), sample.batchId(), sample.item(),
                sample.mode(), sample.createdAt());
    }
}
