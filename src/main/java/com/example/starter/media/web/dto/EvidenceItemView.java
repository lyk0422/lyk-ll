package com.example.starter.media.web.dto;

import com.example.starter.media.domain.GrantStatus;
import com.example.starter.media.domain.LicenseMode;
import com.example.starter.media.domain.ScheduleItemStatus;

import java.time.Instant;

/**
 * 历史播出证据条目：排期时段快照 + 绑定时明确授权版本的不可变信息。
 */
public record EvidenceItemView(
        String scheduleId,
        Integer scheduleVersionNo,
        Integer itemNo,
        Instant slotStart,
        Instant slotEnd,
        ScheduleItemStatus itemStatus,
        Instant playedAt,
        Long boundVersionId,
        Long grantId,
        Integer boundVersionNo,
        Instant grantWindowStart,
        Instant grantWindowEnd,
        LicenseMode grantMode,
        GrantStatus grantVersionStatus
) {
}
