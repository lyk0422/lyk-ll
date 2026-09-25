package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

/**
 * 通风令请求：下达即开始并锁定风机。
 *
 * @param requestId       幂等键
 * @param siloCode        粮仓编号
 * @param fanCode         风机编号
 * @param purpose         通风目的：COOLING 降温 / DEHUMIDIFY 降水 / QUALITY 调质
 * @param ambientHumidity 外界相对湿度，百分比；严格大于粮堆平衡湿度时禁止通风（恰等允许）
 * @param startTime       作业窗起点（含）
 * @param endTime         作业窗终点（不含），必须晚于起点
 */
public record VentilationRequest(
        @NotBlank String requestId,
        @NotBlank String siloCode,
        @NotBlank String fanCode,
        @NotBlank String purpose,
        @NotNull @Positive Double ambientHumidity,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime
) {
}
