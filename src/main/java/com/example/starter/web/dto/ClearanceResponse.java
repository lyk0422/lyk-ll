package com.example.starter.web.dto;

import java.util.List;

/**
 * 救援分段清空确认响应。
 *
 * @param eventId        管制事件 id
 * @param segmentId      分段 id
 * @param confirmerCount 已确认人数（去重）
 * @param cleared        是否已双人确认清空
 * @param confirmers     确认人列表
 */
public record ClearanceResponse(long eventId, long segmentId, int confirmerCount,
                                boolean cleared, List<String> confirmers) {
}
