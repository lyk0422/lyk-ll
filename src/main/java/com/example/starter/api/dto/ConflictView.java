package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 冲突明细视图：某区段上的正长度重叠，按区段与重叠起点排序。
 */
public record ConflictView(
        String sectionId,
        String conflictingRequestId,
        String conflictingStatus,
        Instant conflictingBufferedStart,
        Instant conflictingBufferedEnd,
        Instant overlapStart,
        Instant overlapEnd
) {
}
