package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 油机到位启动后的发电确认回传。时刻以服务端可控时钟为准。
 */
public record ConfirmationRequest(
        @NotBlank String requestId,
        @NotBlank String orderRequestId
) {
}
