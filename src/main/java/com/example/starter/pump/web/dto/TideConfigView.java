package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分区潮汐控制配置查询视图。
 *
 * @param zoneCode           流域分区代码
 * @param backwaterThreshold 潮位顶托门槛（米）
 * @param currentMode        当前工况 NORMAL/FORCED
 * @param version            当前工况版本
 * @param updatedAt          最近变更时刻（Asia/Shanghai）
 */
public record TideConfigView(
        String zoneCode,
        BigDecimal backwaterThreshold,
        String currentMode,
        int version,
        LocalDateTime updatedAt
) {
}
