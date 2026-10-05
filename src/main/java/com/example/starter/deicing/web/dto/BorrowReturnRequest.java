package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 借用归还登记请求体。
 *
 * @param requestId 借用单幂等键
 */
public record BorrowReturnRequest(@NotBlank String requestId) {
}
