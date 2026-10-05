package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 手工生成协查单请求。
 *
 * @param requestId        幂等键，非空；指纹覆盖 requestId 与全部参数
 * @param note             协查说明，可空
 * @param institutionCodes 指定推送机构；空或 null 时默认推送事件涉及卡片的报告机构
 */
public record CreateInvestigationRequest(
        @NotBlank String requestId,
        String note,
        List<String> institutionCodes
) {
}
