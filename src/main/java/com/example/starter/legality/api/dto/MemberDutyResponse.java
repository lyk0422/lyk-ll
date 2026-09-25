package com.example.starter.legality.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 单个成员的判定结果。
 *
 * @param accepted              是否受理计入后续历史（LEGAL 为 true；ILLEGAL 驳回为 false）
 * @param idempotentReplay      是否为同键重放返回的首次判定
 * @param tierLimitMinutes      报到时段分档 FDP 上限（分钟）
 * @param totalExtensionMinutes 已累计延误延长（分钟）
 * @param dutyEnd               实际执勤结束时刻（计划结束 + 延长，UTC）
 * @param reasons               违规原因代码列表，合法为空列表
 */
public record MemberDutyResponse(
        String requestId,
        String memberCode,
        String flightNo,
        Instant reportTime,
        Instant plannedEnd,
        Instant dutyEnd,
        int tierLimitMinutes,
        int totalExtensionMinutes,
        String result,
        List<String> reasons,
        boolean accepted,
        boolean idempotentReplay
) {
}
