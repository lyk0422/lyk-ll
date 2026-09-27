package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分区潮汐控制配置：顶托门槛与工况版本。未配置的分区不参与潮汐工况切换。
 *
 * @param zoneCode  流域分区代码
 * @param threshold 顶托门槛潮位（米，非负）：潮位>=门槛进入强排，恰等进入
 * @param mode      当前工况 NORMAL 常规 / FORCED 强排
 * @param version   工况版本：从1开始，每次成功变更（调门槛/工况切换）加1
 * @param updatedAt 最近变更时刻（Asia/Shanghai）
 */
public record TideControl(
        String zoneCode,
        BigDecimal threshold,
        String mode,
        int version,
        LocalDateTime updatedAt
) {

    /** 退出回差（米）：潮位回落到门槛减0.2米以下才退出强排，恰在回落线仍保持。 */
    public static final BigDecimal EXIT_HYSTERESIS = new BigDecimal("0.2");

    public boolean forced() {
        return "FORCED".equals(mode);
    }

    /** 退出线（米）：门槛减回差。 */
    public BigDecimal exitLine() {
        return threshold.subtract(EXIT_HYSTERESIS);
    }
}
