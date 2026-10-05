package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 借用归还登记请求体。
 */
public record ReturnBorrowRequest(@NotBlank String requestId) {
}
