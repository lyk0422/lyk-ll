package com.example.starter.db;

import java.time.LocalDateTime;

/**
 * 粮仓持久化对象。
 *
 * @param safeTemperature     安全温度，摄氏度
 * @param maxGradient         允许粮温梯度，摄氏度
 * @param equilibriumHumidity 粮堆平衡湿度，百分比
 * @param ventilationRounds   已开始的通风轮次（0-2）
 */
public record SiloPo(
        long id,
        String code,
        String name,
        String grainType,
        double safeTemperature,
        double maxGradient,
        double equilibriumHumidity,
        String status,
        int ventilationRounds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
