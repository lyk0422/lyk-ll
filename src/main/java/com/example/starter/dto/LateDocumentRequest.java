package com.example.starter.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 归档后迟到资料上报请求：仅登记迟到事件，不改变终态。
 */
public record LateDocumentRequest(
        @NotEmpty List<@jakarta.validation.constraints.NotBlank String> documents
) {
}
