package com.example.starter.marpol.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 船舶登记请求（合成数据）。
 */
public record CreateShipRequest(
        @NotBlank @Size(max = 32) String imo,
        @NotBlank @Size(max = 128) String name) {
}
