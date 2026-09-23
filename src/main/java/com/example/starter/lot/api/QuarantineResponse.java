package com.example.starter.lot.api;

import com.example.starter.lot.model.LotStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 隔离操作结果：直接操作批次及受传播影响的后代批次状态。
 */
public record QuarantineResponse(
        String lotId,
        String action,
        LotStatus lotStatus,
        List<String> affectedLotIds,
        LocalDateTime at
) {
}
