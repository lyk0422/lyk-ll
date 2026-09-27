package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 补偿批准请求：受控缸次投料前为每种染料指定批次与系数版本，批准后冻结批次、系数与补偿目标。
 */
public record CompensationRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        @NotEmpty @Valid List<CompensationItemInput> items) {
}
