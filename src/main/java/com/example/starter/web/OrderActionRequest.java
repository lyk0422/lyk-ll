package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 日结封存/通用 requestId 请求。
 */
public record OrderActionRequest(@NotBlank String requestId) {
}
