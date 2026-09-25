package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 评估归档请求。归档为终态：重复归档返回幂等成功。
 *
 * @param requestId   幂等键，指纹覆盖本请求全部业务字段
 * @param stationCode 换热站编码
 */
public record ArchiveRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode
) {
}
