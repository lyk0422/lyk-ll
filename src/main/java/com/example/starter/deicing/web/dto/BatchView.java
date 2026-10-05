package com.example.starter.deicing.web.dto;

import java.time.Instant;

/**
 * 入库批次视图，所有时刻均为 UTC。
 *
 * @param quantityIn        入库量（升）
 * @param quantityAvailable 剩余量（升）= 入库量减已消耗
 * @param expiryTime        效期截止（UTC）
 */
public record BatchView(
        String batchNo,
        String fluidCode,
        int quantityIn,
        int quantityAvailable,
        Instant expiryTime) {
}
