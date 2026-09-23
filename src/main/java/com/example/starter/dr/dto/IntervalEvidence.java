package com.example.starter.dr.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 单个区间的证据版本。
 *
 * @param intervalIdx     事件区间编号，从 0 开始
 * @param baselineVersion 该区间基线点当前必须持有的版本号
 * @param actualVersion   该区间实测点当前必须持有的版本号
 */
public record IntervalEvidence(
        @NotNull Integer intervalIdx,
        @NotNull @Positive Integer baselineVersion,
        @NotNull @Positive Integer actualVersion) {
}
