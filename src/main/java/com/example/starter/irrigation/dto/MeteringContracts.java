package com.example.starter.irrigation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 实供计量相关请求/响应契约。
 */
public final class MeteringContracts {

    private MeteringContracts() {
    }

    /**
     * 实供计量上报请求。amountM3 为该斗口本轮已确认实供水量快照（非增量），
     * 按斗口+轮次去重，重复上报不重复累计。
     *
     * @param outletId 斗口 id
     * @param roundNo  轮次编号
     * @param amountM3 实供水量快照（立方米）
     */
    public record MeteringRequest(
            @NotNull Long outletId,
            @NotNull @Min(0) Integer roundNo,
            @NotNull @PositiveOrZero Long amountM3) {
    }

    /**
     * 计量上报结果。
     *
     * @param outletId 斗口 id
     * @param roundNo  轮次编号
     * @param amountM3 已确认实供水量（立方米）
     * @param status   CONFIRMED 已确认 / LATE 迟到（轮次已封存，仅记录事件）
     */
    public record MeteringView(long outletId, int roundNo, long amountM3, String status) {
    }
}
