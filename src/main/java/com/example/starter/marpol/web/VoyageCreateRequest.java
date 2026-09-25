package com.example.starter.marpol.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建合成航次请求。
 */
public record VoyageCreateRequest(
        @NotBlank String shipId,
        @NotBlank String voyageNo) {
}
