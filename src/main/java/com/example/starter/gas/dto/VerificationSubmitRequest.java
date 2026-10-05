package com.example.starter.gas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 气质组分核验提交请求。
 *
 * @param requestId   幂等键
 * @param stationCode 交接站编码
 * @param gasDate     气量日（UTC）
 * @param samples     本批采样记录（至少一条；与同站同日既有采样合并加权）
 */
public record VerificationSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull LocalDate gasDate,
        @NotNull @NotEmpty List<@Valid VerificationSampleRequest> samples) {
}
