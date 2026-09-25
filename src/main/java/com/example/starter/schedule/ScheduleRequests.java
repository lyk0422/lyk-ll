package com.example.starter.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 排产服务入参 DTO。时间均为 Asia/Shanghai 本地时间，半开区间 [start, end)。
 */
public final class ScheduleRequests {

    private ScheduleRequests() {
    }

    /** 轧制明细：seq 为单元内轧制顺序（从 1 递增）。 */
    public record ItemSpec(
            @NotNull Integer seq,
            @NotBlank String contractNo,
            @NotNull Integer widthMm,
            @NotNull @Positive BigDecimal mileageKm,
            boolean warmup,
            @NotNull LocalDateTime plannedStart,
            @NotNull LocalDateTime plannedEnd) {
    }

    /** 计划换辊窗口。 */
    public record RollWindowSpec(
            @NotNull LocalDateTime start,
            @NotNull LocalDateTime end) {
    }

    /** 编制排产单（幂等）。 */
    public record CreateSchedule(
            @NotBlank String requestId,
            @NotBlank String lineCode,
            @NotBlank String unitNo,
            @NotEmpty List<@Valid ItemSpec> items,
            List<@Valid RollWindowSpec> rollChangeWindows) {
    }

    /** 下达后修改：基于已有排产生成新版本（幂等）。 */
    public record ReviseSchedule(
            @NotBlank String requestId,
            @NotEmpty List<@Valid ItemSpec> items,
            List<@Valid RollWindowSpec> rollChangeWindows) {
    }

    /** 单条实绩：按 合同号+道次 去重归位。 */
    public record ActualItem(
            @NotBlank String contractNo,
            @NotNull Integer passNo,
            @NotNull @Positive BigDecimal mileageKm,
            @NotNull LocalDateTime occurredAt) {
    }

    /** 实绩回传（幂等）。 */
    public record ReportActuals(
            @NotBlank String requestId,
            @NotEmpty List<@Valid ActualItem> actuals) {
    }

    /** 登记换辊：清零本周期实绩里程并解除锁定。 */
    public record RegisterRollChange(
            @NotNull LocalDateTime start,
            @NotNull LocalDateTime end) {
    }
}
