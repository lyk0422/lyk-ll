package com.example.starter.spectrum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 频谱租约协调配置。
 *
 * @param sharedEirpLimitMw 全共享场景下，任意实际重叠的时间×频率×区域片段内
 *                          生效租约 EIRP 总和上限，单位 mW
 */
@ConfigurationProperties(prefix = "spectrum.lease")
public record SpectrumProperties(java.math.BigDecimal sharedEirpLimitMw) {
}
