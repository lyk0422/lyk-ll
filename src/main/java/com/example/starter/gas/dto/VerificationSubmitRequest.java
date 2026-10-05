package com.example.starter.gas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 气质组分核验提交请求：关联交接站、气量日与已有凭证；
 * 同站同日再次提交即修订，追加新版本。
 *
 * @param requestId   幂等键
 * @param stationCode 交接站编码
 * @param gasDate     气量日（UTC）
 * @param voucherNo   关联交接凭证号
 * @param samples     采样记录（至少一条；同站同时刻重复采样去重）
 */
public record VerificationSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull LocalDate gasDate,
        @NotBlank String voucherNo,
        @NotEmpty List<@Valid SampleRequest> samples) {
}
