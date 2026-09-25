package com.example.starter.marpol.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 航次创建请求。
 */
public record CreateVoyageRequest(
        @NotBlank @Size(max = 32) String shipImo,
        @NotBlank @Size(max = 64) String voyageNo) {
}
