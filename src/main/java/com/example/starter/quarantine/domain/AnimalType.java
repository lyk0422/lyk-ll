package com.example.starter.quarantine.domain;

import java.time.Duration;

/**
 * 动物种类。有效期自出证时刻（UTC）起算：猪牛羊 24 小时，禽 48 小时。
 */
public enum AnimalType {

    PIG(Duration.ofHours(24)),
    CATTLE(Duration.ofHours(24)),
    SHEEP(Duration.ofHours(24)),
    POULTRY(Duration.ofHours(48));

    private final Duration certificateValidity;

    AnimalType(Duration certificateValidity) {
        this.certificateValidity = certificateValidity;
    }

    /**
     * @return 检疫合格证有效期（UTC）
     */
    public Duration certificateValidity() {
        return certificateValidity;
    }
}
