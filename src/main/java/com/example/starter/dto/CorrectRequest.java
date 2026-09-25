package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 补正请求：在 5 个工作日时限内一次性补齐资料。
 *
 * @param requestId 幂等键
 * @param documents 补正后完整的资料目录代码（与首次申报合并后核对）
 */
public record CorrectRequest(
        @NotBlank String requestId,
        @NotEmpty List<@NotBlank String> documents
) {
}
