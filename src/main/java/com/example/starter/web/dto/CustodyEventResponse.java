package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 保管事件响应，事件仅追加、不可变。
 */
public record CustodyEventResponse(
        long sequence,
        String evidenceId,
        String eventType,
        String detail,
        Instant eventTime
) {
}
