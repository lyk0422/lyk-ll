package com.example.starter.hazmat.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新增接班员合成资格请求。
 *
 * @param requestId     幂等键（指纹覆盖全部字段与精确时刻）
 * @param escortId      接班员编号（合成数据）
 * @param goodsCategory 可押运危化品类别
 * @param validFromUtc  资格有效区间起点（UTC，闭区间）
 * @param validToUtc    资格有效区间终点（UTC，闭区间），须严格晚于起点
 */
public record QualificationCreateRequest(
        @NotBlank String requestId,
        @NotBlank String escortId,
        @NotBlank String goodsCategory,
        @NotNull LocalDateTime validFromUtc,
        @NotNull LocalDateTime validToUtc) {
}
