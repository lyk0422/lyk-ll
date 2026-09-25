package com.example.starter.cutover.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 步骤回报请求；requestId 为幂等键，指纹覆盖 applicationId+stepSeq+phase+result。
 *
 * @param phase  START 开始执行（启动 10 分钟验证计时）；RESULT 回报结果
 * @param result 仅 RESULT 阶段必填：SUCCESS / FAIL / HARD_FAIL
 */
public record ReportRequest(
        @NotBlank String requestId,
        @NotBlank String phase,
        String result
) {
}
