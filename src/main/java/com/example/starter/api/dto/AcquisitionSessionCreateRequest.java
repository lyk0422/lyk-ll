package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建本地力矩采集会话请求：绑定工序卡、力矩工具（含日校验版本快照）与紧固点清单。
 * 设备不联网，数据仅经本地 HTTP 提交。
 */
public record AcquisitionSessionCreateRequest(
        @NotBlank String requestId,
        @NotBlank String sessionNo,
        @NotBlank String cardNo,
        @NotBlank String toolCode,
        @NotNull @Size(min = 1) List<@NotBlank String> pointCodes) {
}
