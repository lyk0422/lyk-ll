package com.example.starter.cosmetic.web.dto;

import java.time.Instant;

/**
 * 未领标签销毁登记结果响应。
 *
 * @param httpStatus       等价 HTTP 状态码，便于幂等快照复算
 * @param printRunNo       印次号
 * @param quantity         本次销毁张数
 * @param printRunVersion  销毁后的印次台账版本
 * @param remainingAfter   销毁后未领余额
 * @param idempotentReplay 是否为同键同参重放（返回首次结果）
 * @param atUtc            销毁登记时刻(UTC)
 */
public record DestroyResponse(
        int httpStatus,
        String printRunNo,
        int quantity,
        int printRunVersion,
        int remainingAfter,
        boolean idempotentReplay,
        Instant atUtc) {
}
