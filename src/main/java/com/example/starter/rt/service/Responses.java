package com.example.starter.rt.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.example.starter.rt.domain.PlanStatus;
import com.example.starter.rt.dto.PlanVersionResponse;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.web.ClockConfig;

/**
 * 仓储行到响应 DTO 的映射工具；时间统一按 Asia/Shanghai 偏移输出。
 */
public final class Responses {

    private static final ZoneOffset OFFSET = ClockConfig.BUSINESS_ZONE.getRules().getOffset(LocalDateTime.now());

    private Responses() {
    }

    public static OffsetDateTime odt(LocalDateTime time) {
        return time == null ? null : time.atOffset(OFFSET);
    }

    public static PlanVersionResponse toPlanVersion(PlanVersionRow row) {
        return new PlanVersionResponse(
                row.id(),
                row.patientId(),
                row.planCode(),
                row.versionNo(),
                row.templateCode(),
                row.prescribedTotalDoseCgy(),
                row.fractionDoseCgy(),
                row.fractionCount(),
                PlanStatus.valueOf(row.status()),
                odt(row.createdAt()),
                odt(row.reviewedAt()),
                odt(row.approvedAt()),
                odt(row.verifiedAt()),
                odt(row.firstFractionAt()),
                odt(row.closedAt()),
                odt(row.invalidatedAt()));
    }
}
