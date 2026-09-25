package com.example.starter.web.dto;

import com.example.starter.web.dto.ApplicationRequest.DocumentItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 补正请求：以 requestId 幂等，须一次性补齐全部缺项。
 *
 * @param requestId 幂等请求ID
 * @param documents 补正提交的资料目录项
 */
public record CorrectionRequest(
        @NotBlank String requestId,
        @NotNull List<@Valid DocumentItem> documents) {
}
