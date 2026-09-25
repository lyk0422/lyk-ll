package com.example.starter.irrigation.api;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/**
 * 轮次结束欠供结转封存报文。封存为终态：重复封存幂等成功，迟到计量只登记不改结果。
 */
public final class SettlementDtos {

    private SettlementDtos() {
    }

    /**
     * 封存请求。
     *
     * @param turnNo 轮次编号；为 null 时取请求时刻的当前 UTC 轮次
     */
    public record SealRequest(
            @NotBlank String groupCode,
            Long turnNo) {
    }

    /** 封存结果响应（重复封存返回首次封存结果）。 */
    public record SettlementResponse(
            long turnNo,
            String groupCode,
            String status,
            boolean idempotentReplay,
            Instant sealedAt,
            List<CarryoverLine> lines) {
    }

    /** 单斗口欠供结转明细，单位均为立方米。 */
    public record CarryoverLine(
            String outletCode,
            long planCubicMeter,
            long deliveredCubicMeter,
            long shortageCubicMeter,
            long carryoverCubicMeter,
            long voidedCubicMeter) {
    }
}
