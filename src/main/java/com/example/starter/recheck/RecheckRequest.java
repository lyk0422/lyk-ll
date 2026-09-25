package com.example.starter.recheck;

import com.example.starter.domain.RecheckResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 定量复检请求。requestId 为幂等键；result 为 PASS 合格 / FAIL 不合格。
 */
public record RecheckRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "复检结果不能为空") RecheckResult result) {
}
