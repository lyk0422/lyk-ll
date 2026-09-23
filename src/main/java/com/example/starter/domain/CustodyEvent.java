package com.example.starter.domain;

import java.time.Instant;

/**
 * 保管事件表行记录，仅追加。
 */
public record CustodyEvent(
        long id,
        String evidenceId,
        CustodyEventType eventType,
        String detail,
        Instant eventTime
) {
}
