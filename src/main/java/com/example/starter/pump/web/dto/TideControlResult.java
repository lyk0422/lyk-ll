package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 潮汐控制配置结果。
 *
 * @param zoneCode  流域分区代码
 * @param threshold 顶托门槛潮位（米）
 * @param mode      当前工况 NORMAL/FORCED
 * @param version   当前工况版本
 * @param updatedAt 最近变更时刻
 * @param replayed  是否为同键重放返回
 */
public record TideControlResult(
        String zoneCode,
        BigDecimal threshold,
        String mode,
        int version,
        LocalDateTime updatedAt,
        boolean replayed
) {
}
