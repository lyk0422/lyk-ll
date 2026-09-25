package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 告警/事件行。type：CURTAIL_TIMEOUT 超时未达目标；LATE_REPORT 封存后迟到回报。
 */
public record AlarmRow(long id, long commandId, Long turbineId, String type, String message,
                       LocalDateTime createdAt) {

    public static final String TYPE_CURTAIL_TIMEOUT = "CURTAIL_TIMEOUT";
    public static final String TYPE_LATE_REPORT = "LATE_REPORT";
}
