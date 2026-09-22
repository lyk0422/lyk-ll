package com.example.starter.bag.web.dto;

import com.example.starter.bag.model.SegmentStatus;

/**
 * 逻辑分段视图。纳秒与序列号边界在空段时为 null。
 */
public record SegmentView(
        long id,
        String topic,
        int segIndex,
        Long startNs,
        Long endNs,
        Long startSeq,
        Long endSeq,
        long messageCount,
        int maxSize,
        SegmentStatus status,
        String checksum) {
}
