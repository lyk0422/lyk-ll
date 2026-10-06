package com.example.starter.web.dto;

import java.time.LocalDate;

/**
 * 日结行：按日期+线路汇总改派与通知数量，可复算（重复执行结果一致）。
 *
 * @param settleDate      日结日期（UTC）
 * @param routeId         线路 ID
 * @param reassignedCount 当日改派学生数
 * @param notifiedCount   当日通知数
 */
public record SettlementRow(LocalDate settleDate, Long routeId, int reassignedCount, int notifiedCount) {
}
