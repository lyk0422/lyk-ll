package com.example.starter.irrigation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

/**
 * 临时加购水量报文：加购水量优先满足欠供斗口，按分配时点欠供比例分配。
 */
public final class PurchaseDtos {

    private PurchaseDtos() {
    }

    /**
     * 加购请求。
     *
     * @param turnNo 轮次编号；为 null 时取请求时刻的当前 UTC 轮次
     */
    public record ExtraPurchaseRequest(
            @NotBlank String requestId,
            @NotBlank String groupCode,
            Long turnNo,
            @NotNull @Positive Long amountCubicMeter) {
    }

    /** 加购分配响应。 */
    public record ExtraPurchaseResponse(
            long purchaseId,
            String requestId,
            String groupCode,
            long turnNo,
            long amountCubicMeter,
            long allocatedCubicMeter,
            Instant createdAt,
            List<ExtraAllocationLine> lines) {
    }

    /** 单个欠供斗口的获配明细。 */
    public record ExtraAllocationLine(
            String outletCode,
            long shortageCubicMeter,
            long allocatedCubicMeter) {
    }
}
