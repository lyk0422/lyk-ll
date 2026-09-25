package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 限电指令受理结果。replayed=true 表示同键同参幂等重放，返回首次受理结果。
 */
public record CommandAccepted(
        long commandId,
        Long supersededCommandId,
        String status,
        int targetKw,
        int totalAvailableKw,
        int reductionKw,
        boolean curtailed,
        LocalDateTime issuedAt,
        List<AllocationView> allocations,
        boolean replayed) {

    /**
     * 单机分摊视图。
     */
    public record AllocationView(long turbineId, String turbineCode, int availableKw, int minKw,
                                 int headroomKw, int targetKw) {
    }
}
