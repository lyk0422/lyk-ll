package com.example.starter.hazmat.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新增接班员合成资格请求。
 *
 * @param requestId     幂等键（指纹含全部字段与精确时刻；失败不占键）
 * @param escortId      接班员编号（合成数据）
 * @param goodsCategory 可押运货类
 * @param validFrom     资格有效区间起点（UTC，闭区间）
 * @param validTo       资格有效区间终点（UTC，闭区间），须严格晚于起点
 */
public record QualificationRequest(
        @NotBlank String requestId,
        @NotBlank String escortId,
        @NotBlank String goodsCategory,
        @NotNull LocalDateTime validFrom,
        @NotNull LocalDateTime validTo) {
}
