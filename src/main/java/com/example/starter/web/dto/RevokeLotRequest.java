package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 批次注销请求（注销为终态，重复注销按幂等成功返回）。
 *
 * @param reason   注销原因
 * @param operator 操作人（合成数据）
 */
public record RevokeLotRequest(
        @NotBlank @Size(max = 256) String reason,
        @NotBlank @Size(max = 64) String operator
) {
}
