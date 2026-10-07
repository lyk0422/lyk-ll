package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 受控跑道登记请求。
 *
 * @param requestId    幂等请求标识
 * @param runwayId     跑道编号（合成数据；合法文本可含分隔符）
 * @param lengthMeters 跑道长度（单位：米，正整数）
 */
public record RegisterRunwayRequest(
        @NotBlank String requestId,
        @NotBlank String runwayId,
        @Positive int lengthMeters) {
}
