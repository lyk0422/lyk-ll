package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 途中异常登记请求（仅模拟软件状态，不控制列车）。
 *
 * @param requestId 幂等请求键
 * @param reason    途中异常说明（合成数据）
 */
public record EnRouteAbnormalityRequest(
        @NotBlank String requestId,
        @NotBlank @Size(max = 512) String reason) {
}
