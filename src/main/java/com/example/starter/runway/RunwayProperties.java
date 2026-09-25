package com.example.starter.runway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 跑道业务规则配置（合成阈值）。
 *
 * @param lowVisibilityRvrThreshold 低能见度RVR阈值（米）：最新读数低于该值时巡检须双人执行
 * @param inspectionStartLimitMinutes 巡检开始时限（分钟）：恰满即迟延并升级
 * @param frictionPassThreshold 摩擦系数合格阈值：低于持续关闭，恰等于合格
 */
@ConfigurationProperties(prefix = "runway")
public record RunwayProperties(
        int lowVisibilityRvrThreshold,
        int inspectionStartLimitMinutes,
        BigDecimal frictionPassThreshold) {
}
