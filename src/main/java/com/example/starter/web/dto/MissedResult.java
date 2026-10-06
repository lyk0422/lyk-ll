package com.example.starter.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 漏乘名单结果：实时生成合成通知记录（按线路+学生+日期+类型去重）。
 *
 * @param routeId               线路 ID
 * @param date                  统计日期（UTC）
 * @param missedStudentIds      漏乘学生 ID 列表
 * @param notificationsGenerated 本次新生成的通知记录数
 */
public record MissedResult(Long routeId, LocalDate date, List<Long> missedStudentIds,
                           int notificationsGenerated) {
}
