package com.example.starter.deicing.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 库存台账响应：按除冰液编码排序，重复查询稳定。
 */
public record LedgerResponse(
        Instant asOf,
        List<FluidLedgerView> fluids) {
}
