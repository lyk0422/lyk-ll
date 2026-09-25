package com.example.starter.ili.dto;

import java.time.Instant;

/**
 * 封板结果视图；封板为终态，重复封板返回相同的首次封板时间。
 *
 * @param batchId  批次主键
 * @param status   批次状态，恒为 SEALED
 * @param sealedAt 首次封板时间（UTC）
 */
public record SealResponse(
        long batchId,
        String status,
        Instant sealedAt) {
}
