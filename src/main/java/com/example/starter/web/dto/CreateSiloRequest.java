package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建粮仓（合成数据维护入口）。
 *
 * @param safeTemperature     安全温度，摄氏度；任一点粮温严格大于该值告警
 * @param maxGradient         允许粮温梯度，摄氏度；复测最高温减最低温严格大于该值判超标
 * @param equilibriumHumidity 粮堆平衡湿度，百分比；外界湿度严格大于该值禁止通风
 */
public record CreateSiloRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String grainType,
        double safeTemperature,
        double maxGradient,
        double equilibriumHumidity
) {
}
