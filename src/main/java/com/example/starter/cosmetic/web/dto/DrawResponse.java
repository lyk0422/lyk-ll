package com.example.starter.cosmetic.web.dto;

import java.time.Instant;

/**
 * 标签领用结果响应。
 *
 * @param httpStatus       等价 HTTP 状态码，便于幂等快照复算
 * @param printRunNo       印次号
 * @param batchNo          包装批号
 * @param productCode      产品编码
 * @param quantity         本次领用张数
 * @param printRunVersion  领用后的印次台账版本
 * @param remainingAfter   领用后未领余额
 * @param idempotentReplay 是否为同键同参重放（返回首次结果）
 * @param atUtc            领用时刻(UTC)
 */
public record DrawResponse(
        int httpStatus,
        String printRunNo,
        String batchNo,
        String productCode,
        int quantity,
        int printRunVersion,
        int remainingAfter,
        boolean idempotentReplay,
        Instant atUtc) {
}
