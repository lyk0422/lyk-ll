package com.example.starter.forestry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 采伐监管业务参数。
 *
 * @param blacklistDays      列入限批名单的时长（天），期限内新申请一律拒绝
 * @param harvestWindowDays  发证后采伐期限（自然日，UTC 日界）
 * @param acceptancePassRate 更新验收合格率下限（百分比），恰达该值合格
 */
@ConfigurationProperties(prefix = "forestry")
public record ForestryProperties(int blacklistDays, int harvestWindowDays, int acceptancePassRate) {
}
