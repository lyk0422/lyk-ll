package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 接入请求: 建立列车作业并登记初始编组。
 *
 * @param requestId       幂等键
 * @param trackNo         股道号(同股道同时刻只允许一列作业中列车)
 * @param trainNo         车次
 * @param setPressureKpa  制动管定压(kPa)
 * @param cars            初始编组车辆清单
 */
public record ReceiveRequest(
        @NotBlank String requestId,
        @NotBlank String trackNo,
        @NotBlank String trainNo,
        @Min(1) int setPressureKpa,
        @NotEmpty List<@Valid CarInput> cars) {
}
