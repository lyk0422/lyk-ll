package com.example.starter.marpol.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建合成船舶请求。
 */
public record ShipCreateRequest(
        @NotBlank String shipId,
        @NotBlank String shipName) {
}
