package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 受控跑道长度修改请求：存在活动施工后不许缩改。
 *
 * @param requestId       幂等请求标识
 * @param expectedVersion 期望的跑道聚合版本；不符返回409
 * @param lengthMeters    新跑道长度（单位：米，正整数）
 */
public record UpdateRunwayLengthRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion,
        @Positive int lengthMeters) {
}
