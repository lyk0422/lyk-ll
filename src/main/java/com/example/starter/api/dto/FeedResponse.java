package com.example.starter.api.dto;

import java.time.LocalDateTime;

/**
 * 投料受理结果。
 *
 * @param requestId  投料幂等键
 * @param kilnId     窑编号
 * @param operatorId 操作人编号
 * @param fedAt      投料受理时刻（Asia/Shanghai）
 * @param accepted   是否受理（曲线已走完方受理）
 */
public record FeedResponse(
        String requestId,
        String kilnId,
        String operatorId,
        LocalDateTime fedAt,
        boolean accepted) {
}