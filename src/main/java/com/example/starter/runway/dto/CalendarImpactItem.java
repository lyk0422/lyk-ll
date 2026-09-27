package com.example.starter.runway.dto;

/**
 * 日历影响项：描述单个尚未开始计划受日历版本变更的影响。
 *
 * @param planId                  计划ID
 * @param runwayId                跑道编号
 * @param frozenCalendarVersion   计划冻结所依据的日历版本
 * @param currentCalendarVersion  当前日历版本
 * @param status                  影响后计划状态（REVIEW_PENDING；RUNNING 及终态不在影响列表）
 */
public record CalendarImpactItem(
        long planId,
        String runwayId,
        int frozenCalendarVersion,
        int currentCalendarVersion,
        String status) {
}
