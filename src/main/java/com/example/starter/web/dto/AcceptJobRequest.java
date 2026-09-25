package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 列车接入请求。
 *
 * @param requestId 幂等请求键
 * @param trainNo   车次（合成数据）
 * @param track     股道号
 * @param cars      编组车辆清单
 */
public record AcceptJobRequest(
        @NotBlank String requestId,
        @NotBlank String trainNo,
        @NotBlank String track,
        @NotEmpty List<@Valid CarDto> cars) {
}
