package com.example.starter.deicing.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 库存台账快照，可复算、重复查询稳定。
 */
public record LedgerResponse(Instant asOf, List<FluidLedgerView> fluids) {
}
