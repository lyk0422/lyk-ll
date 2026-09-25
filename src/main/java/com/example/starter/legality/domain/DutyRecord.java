package com.example.starter.legality.domain;

import java.time.OffsetDateTime;

/**
 * 机组飞行执勤记录。所有时刻统一以 UTC 存储。
 *
 * @param requestId        幂等请求ID
 * @param batchId          所属校验批次ID，{@code null} 表示单独报到尚未归入批次
 * @param crewId           机组成员ID（合成数据）
 * @param flightNo         航班号（合成数据）
 * @param reportTime       报到时刻，UTC
 * @param scheduledEndTime 计划执勤结束时刻，UTC
 * @param delayMinutes     封板前已受理的延误延长累计，单位分钟
 * @param legal            成员级合规判定
 * @param reasons          违规原因码，逗号分隔，空串表示合法
 * @param createdTime      记录创建时刻，UTC
 */
public record DutyRecord(
        String requestId,
        String batchId,
        String crewId,
        String flightNo,
        OffsetDateTime reportTime,
        OffsetDateTime scheduledEndTime,
        int delayMinutes,
        boolean legal,
        String reasons,
        OffsetDateTime createdTime) {

    /** 实际执勤结束时刻（UTC）= 计划结束 + 已受理延误延长。 */
    public OffsetDateTime actualEndTime() {
        return scheduledEndTime.plusMinutes(delayMinutes);
    }
}
