package com.example.starter.api.dto;

import java.time.LocalDateTime;

/**
 * 恢复投料结果。
 *
 * @param feedId    投料记录主键
 * @param requestId 投料幂等键
 * @param kilnId    窑编号
 * @param ticketNo  校验通过的升温票编号（曲线已走完）
 * @param fedAt     投料受理时刻
 */
public record FeedResponse(
        Long feedId,
        String requestId,
        String kilnId,
        String ticketNo,
        LocalDateTime fedAt) {
}
