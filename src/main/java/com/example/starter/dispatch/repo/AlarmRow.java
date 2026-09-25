package com.example.starter.dispatch.repo;

import java.time.Instant;

/**
 * alarm_event 表行记录：偏差自动告警或超时升级通知上级的留痕。
 */
public record AlarmRow(
        long id,
        long orderId,
        String alarmType,
        String gateCode,
        String detail,
        Instant createdAt) {
}
