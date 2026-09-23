package com.example.starter.calibration.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 审批 / 全量生效 / 回滚等状态转换请求。
 *
 * @param operator 操作者
 * @param reason   可选原因（回滚时建议填写）
 */
public record StateTransitionRequest(
        @NotBlank String operator,
        String reason) {
}
