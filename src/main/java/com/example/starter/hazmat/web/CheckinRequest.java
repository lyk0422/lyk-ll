package com.example.starter.hazmat.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 押运打卡请求。
 *
 * @param requestId   幂等请求标识
 * @param waybillId   运单主键
 * @param waypointSeq 途经点序号（从 1 开始）
 * @param checkinTime 打卡时刻（Asia/Shanghai）
 */
public record CheckinRequest(
        @NotBlank String requestId,
        @NotNull Long waybillId,
        @NotNull @Min(1) Integer waypointSeq,
        @NotNull LocalDateTime checkinTime) {
}
