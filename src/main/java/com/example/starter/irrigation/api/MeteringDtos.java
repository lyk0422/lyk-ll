package com.example.starter.irrigation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * 实供计量上报报文。按“斗口＋轮次”去重：每斗口每轮只保留最后一次已确认累计读数。
 */
public final class MeteringDtos {

    private MeteringDtos() {
    }

    /**
     * 计量上报请求。
     *
     * @param eventId               上报事件幂等键，重复事件不重复处理
     * @param deliveredCubicMeter   本轮截至当前的累计实供读数（立方米），后到读数直接覆盖旧读数
     * @param turnNo                轮次编号；为 null 时取请求时刻的当前 UTC 轮次
     */
    public record MeterReportRequest(
            @NotBlank String eventId,
            @NotBlank String groupCode,
            @NotBlank String outletCode,
            Long turnNo,
            @NotNull @PositiveOrZero Long deliveredCubicMeter) {
    }

    /** 计量快照响应。 */
    public record MeterSnapshotResponse(
            long turnNo,
            String groupCode,
            String outletCode,
            long deliveredCubicMeter,
            int eventCount,
            boolean duplicate,
            boolean late,
            Instant confirmedAt) {
    }
}
