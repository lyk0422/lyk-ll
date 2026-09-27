package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分区潮汐控制配置：未配置的分区不参与潮汐工况，保持原行为。
 *
 * @param zoneCode           流域分区代码
 * @param backwaterThreshold 潮位顶托门槛（米），非负；恰达进入强排
 * @param currentMode        当前工况 NORMAL常规/FORCED强排
 * @param version            工况版本，从1开始，每次成功变更加1
 * @param updatedAt          最近变更时刻（Asia/Shanghai）
 */
public record TideZoneConfig(
        String zoneCode,
        BigDecimal backwaterThreshold,
        String currentMode,
        int version,
        LocalDateTime updatedAt
) {

    public boolean forced() {
        return "FORCED".equals(currentMode);
    }
}
