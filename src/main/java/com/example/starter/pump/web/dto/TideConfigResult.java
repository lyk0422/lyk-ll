package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 分区潮汐控制配置新增/修改结果。
 *
 * @param zoneCode       流域分区代码
 * @param thresholdLevel 顶托门槛潮位（米）
 * @param backoff        回落差（米，固定0.2）
 * @param regimeVersion  变更后的工况配置版本
 * @param created        是否首次新增（true 新建版本1；false 修改后版本加1）
 * @param replayed       是否同键同参重放
 */
public record TideConfigResult(
        String zoneCode,
        BigDecimal thresholdLevel,
        BigDecimal backoff,
        Integer regimeVersion,
        boolean created,
        boolean replayed
) {
}
