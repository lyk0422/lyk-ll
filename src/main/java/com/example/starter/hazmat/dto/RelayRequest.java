package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 设置安全接力点请求（夜间接力押运）。
 *
 * @param requestId      幂等键
 * @param waypointSeq    安全接力点途经点序号（计划通过时刻须早于夜间禁行起点）
 * @param outgoingEscort 交班押运员编号
 * @param incomingEscort 接班押运员编号（须与交班不同）
 */
public record RelayRequest(
        @NotBlank String requestId,
        @PositiveOrZero int waypointSeq,
        @NotBlank String outgoingEscort,
        @NotBlank String incomingEscort) {
}
