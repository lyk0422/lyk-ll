package com.example.starter.web.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * 资格与日结相关请求与响应DTO。
 */
public final class QualificationDtos {

    private QualificationDtos() {
    }

    /**
     * 资格续期请求；仅受理到期前 30 日内（含恰第 30 日）的申请。
     */
    public record RenewalRequest(@NotBlank String requestId) {
    }

    /**
     * 资格响应。
     */
    public record QualificationResponse(long id, long routeId, LocalDate validFrom,
                                        LocalDate validTo, String status, String source) {
    }

    /**
     * 到期资格处理请求（定时任务/运维入口）。
     */
    public record ExpireRequest(@NotBlank String requestId) {
    }

    /**
     * 到期处理响应：失效资格、停运线路与改派/通知数量。
     */
    public record ExpireResponse(List<Long> expiredQualificationIds,
                                 List<Long> suspendedRouteIds,
                                 int reassignmentCount,
                                 int notificationCount) {
    }

    /**
     * 日结响应：打卡、漏乘通知与改派记录按日结日期复算。
     */
    public record SettlementResponse(LocalDate date, long checkInCount, long missedNoticeCount,
                                     long reassignmentCount,
                                     List<ReassignmentItem> reassignments) {
    }

    /**
     * 日结中的改派明细。
     */
    public record ReassignmentItem(long studentId, long fromRouteId, Long toRouteId,
                                   String reason) {
    }
}
