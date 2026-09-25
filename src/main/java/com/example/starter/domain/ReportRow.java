package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 执行回报行。late=true 表示封存/作废后的迟到回报，仅记录事件不参与分摊。
 */
public record ReportRow(long id, long commandId, long farmId, long turbineId, String requestId,
                        String fingerprint, LocalDateTime reportTime, int actualKw, boolean late,
                        LocalDateTime receivedAt) {
}
