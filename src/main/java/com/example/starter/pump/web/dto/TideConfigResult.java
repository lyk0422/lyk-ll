package com.example.starter.pump.web.dto;

import java.math.BigDecimal;

/**
 * 分区潮汐控制配置结果。
 *
 * @param zoneCode           流域分区代码
 * @param backwaterThreshold 潮位顶托门槛（米）
 * @param currentMode        当前工况 NORMAL常规/FORCED强排
 * @param version            当前工况版本
 * @param replayed           是否命中同键重放
 */
public record TideConfigResult(
        String zoneCode,
        BigDecimal backwaterThreshold,
        String currentMode,
        int version,
        boolean replayed
) {
}
