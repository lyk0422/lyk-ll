package com.example.starter.crane.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 塔机吊钩位姿读数上报，1 秒粒度。
 *
 * @param craneCode        塔机编号
 * @param ts               采样时刻（UTC）
 * @param zoneCode         吊钩所在重叠区编号；为空表示不在任何重叠区
 * @param slewingAngleDeg  回转角度，度，可空
 * @param amplitudeM       幅度，米，可空
 * @param hookHeightM      吊钩高度，米，可空
 */
public record PoseRequest(
        @NotBlank String craneCode,
        @NotNull Instant ts,
        String zoneCode,
        BigDecimal slewingAngleDeg,
        BigDecimal amplitudeM,
        BigDecimal hookHeightM) {
}
