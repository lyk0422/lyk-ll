package com.example.starter.spectrum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 频谱协调相关配置。
 *
 * @param sharedEirpLimitMw 共享模式下，任意实际重叠的时间 × 频率 × 区域片段内，
 *                          生效租约 EIRP（mW，线性毫瓦）总和上限。
 */
@ConfigurationProperties(prefix = "spectrum")
public record SpectrumProperties(double sharedEirpLimitMw) {
}
