package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅携带 requestId 的幂等操作请求（审核、撤批、结案）。
 */
public record RequestIdRequest(@NotBlank String requestId) {
}
