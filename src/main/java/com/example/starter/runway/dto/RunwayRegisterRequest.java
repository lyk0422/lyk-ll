package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 受控跑道登记请求：登记正整数米长度。requestId 为幂等标识。
 *
 * @param requestId    幂等请求标识（指纹覆盖该字段与全部业务字段）
 * @param runwayId     跑道编号（合成数据）
 * @param lengthMeters 跑道长度（单位：米，正整数）
 */
public record RunwayRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @Positive int lengthMeters) {
}
