package com.example.starter.cutover.dto;

import java.time.LocalDateTime;

/**
 * 割接事件视图（幂等记录、迟到事件、回退判据命中记录均可复算查询）。
 */
public record EventView(
        String requestUid,
        String requestId,
        String eventType,
        Integer seqNo,
        boolean lateFlag,
        LocalDateTime createdAt
) {
}
