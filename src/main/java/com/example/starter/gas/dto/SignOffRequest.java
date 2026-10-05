package com.example.starter.gas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 气质差异签认请求：双方均签认后解除关联凭证的确认暂停。
 *
 * @param requestId 幂等键
 * @param party     签认方：A/B
 */
public record SignOffRequest(
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "A|B") String party) {
}
