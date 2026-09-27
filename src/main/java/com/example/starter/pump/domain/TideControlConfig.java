package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分区潮汐顶托控制配置（合成）。
 *
 * @param zoneCode       流域分区代码
 * @param thresholdLevel 顶托门槛潮位（米）；潮位大于等于此值进入强排，恰等进入
 * @param backoff        强排退出回落差（米）；潮位严格低于门槛-回落差才退出，恰在回落线保持强排
 * @param regimeVersion  工况配置版本，从1开始，每次成功修改加1
 * @param updatedAt      配置最近修改时刻（Asia/Shanghai）
 */
public record TideControlConfig(
        String zoneCode,
        BigDecimal thresholdLevel,
        BigDecimal backoff,
        Integer regimeVersion,
        LocalDateTime updatedAt
) {
    /** 固定回落差：门槛减0.2米为回落线。 */
    public static final BigDecimal BACKOFF = new BigDecimal("0.200");

    /** 强排退出回落线（米）：门槛 - 0.2。 */
    public BigDecimal exitLine() {
        return thresholdLevel.subtract(backoff);
    }
}
