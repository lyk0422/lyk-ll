package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 并缸请求：将 lotNo 缸次并入 targetLotNo。
 * 旧非受控缸次：同配方同浴比即可，expectedVersion 不传，保持原语义。
 * 两个受控缸次并缸：除同配方同浴比外，还须补偿批次与目标完全相同，且 expectedVersion
 * 为目标缸次补偿批准的当前版本（并缸成功按合并布重重冻补偿，版本加 1）。
 */
public record MergeLotsRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        @NotBlank String targetLotNo,
        Integer expectedVersion) {
}
