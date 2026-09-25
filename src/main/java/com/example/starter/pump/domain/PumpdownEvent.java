package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预降事件：气象预警决策的产物。
 *
 * @param id          事件ID
 * @param requestId   触发预警的幂等请求ID
 * @param zoneCode    流域分区代码
 * @param alertLevel  触发时预警级别代码
 * @param status      ACTIVE 预降中 / ARCHIVED 已归档终态
 * @param targetLevel 分区预降目标水位（米，取分区内泵站最严格目标位）
 * @param startedAt   预降开始时刻
 * @param archivedAt  归档时刻，NULL 表示未归档
 */
public record PumpdownEvent(
        Long id,
        String requestId,
        String zoneCode,
        String alertLevel,
        String status,
        BigDecimal targetLevel,
        LocalDateTime startedAt,
        LocalDateTime archivedAt
) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }

    public boolean archived() {
        return "ARCHIVED".equals(status);
    }
}
