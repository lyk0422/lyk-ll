package com.example.starter.reinsurance.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 再保人确认账单请求。双方确认后封存；重复确认返回幂等成功。
 *
 * @param requestId 幂等键
 */
public record ConfirmBillRequest(@NotBlank String requestId) {
}
