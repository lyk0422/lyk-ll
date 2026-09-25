package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 指令详情：含全部分摊轮次，可复算。
 */
public record CommandDetail(
        long commandId,
        long farmId,
        String requestId,
        int targetKw,
        String status,
        LocalDateTime issuedAt,
        LocalDateTime sealedAt,
        List<RoundAllocations> rounds) {

    /**
     * 一轮分摊明细。
     */
    public record RoundAllocations(int round, List<CommandAccepted.AllocationView> allocations) {
    }
}
