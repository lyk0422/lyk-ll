package com.example.starter.sensor.api;

/**
 * 时钟漂移校正参数版本视图。
 *
 * @param id          版本记录 ID
 * @param sensor      传感器类型
 * @param version     版本号（自增）
 * @param slope       漂移斜率
 * @param offsetNanos 固定偏移（纳秒）
 * @param effectiveAt 生效时刻（epoch 纳秒）
 */
public record CorrectionResponse(
        long id,
        String sensor,
        long version,
        double slope,
        long offsetNanos,
        long effectiveAt) {
}
