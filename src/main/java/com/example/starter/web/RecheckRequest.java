package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 复检申请请求。
 *
 * @param requestId 复检受理幂等键
 * @param lotNo     批次号
 */
public record RecheckRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 40) String lotNo) {
}
