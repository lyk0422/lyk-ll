package com.example.starter.pest;

import java.time.LocalDateTime;

/**
 * 虫情处置链数据行；时间字段均为 UTC，空表示该阶段尚未发生。
 *
 * @param id 主键
 * @param zoneId 所属库区
 * @param status 状态：REPORTED/CONFIRMED/TREATED/RELEASE_APPLIED/RELEASED
 * @param reporter 上报人
 * @param description 虫情描述
 * @param reportedAt 上报时间（UTC）
 * @param confirmedAt 确认时间（UTC），确认后库区进入处置状态
 * @param treatedAt 最近一次投放生效时间（UTC），14 天监测期起点
 * @param releaseAppliedAt 解除申请时间（UTC）
 * @param disinfectConfirmedAt 消杀方确认时间（UTC）
 * @param storageConfirmedAt 库房方确认时间（UTC）
 * @param releasedAt 解除完成时间（UTC）
 */
public record CaseRow(long id, long zoneId, String status, String reporter, String description,
        LocalDateTime reportedAt, LocalDateTime confirmedAt, LocalDateTime treatedAt,
        LocalDateTime releaseAppliedAt, LocalDateTime disinfectConfirmedAt,
        LocalDateTime storageConfirmedAt, LocalDateTime releasedAt) {
}
