package com.example.starter.dye.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 判定归档请求。归档为终态：重复归档按幂等成功返回首次结果。
 */
public record ArchiveRequest(
        @NotBlank String requestId,
        @NotBlank String runNo) {
}
