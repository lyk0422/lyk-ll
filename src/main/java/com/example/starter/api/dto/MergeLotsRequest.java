package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 并缸请求：将 lotNo 缸次并入 targetLotNo；须同配方同浴比。
 */
public record MergeLotsRequest(
        @NotBlank String requestId,
        @NotBlank String lotNo,
        @NotBlank String targetLotNo) {
}
