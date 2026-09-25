package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 批准展期：批准与原证书有效期更新在同一事务内完成。 */
public record ApproveExtensionRequest(
        @NotBlank String requestId,
        @NotNull Long extensionId
) {
}
