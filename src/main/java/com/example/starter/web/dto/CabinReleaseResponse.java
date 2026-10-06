package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 吊厢分段放行响应。
 *
 * @param eventId    管制事件 id
 * @param segmentId  分段 id
 * @param released   是否已放行
 * @param releasedAt 放行时间（UTC）
 */
public record CabinReleaseResponse(long eventId, long segmentId, boolean released, Instant releasedAt) {
}
