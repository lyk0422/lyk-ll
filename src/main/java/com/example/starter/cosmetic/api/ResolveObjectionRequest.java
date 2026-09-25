package com.example.starter.cosmetic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 异议审查结论请求；outcome 取 DISMISSED（异议不成立）或 UPHELD（异议成立）。
 */
public record ResolveObjectionRequest(@NotBlank(message = "审查结论必填") String outcome) {
}
