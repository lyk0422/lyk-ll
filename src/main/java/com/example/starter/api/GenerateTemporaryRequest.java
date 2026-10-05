package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 节假日临时加钞计划生成请求。按合成节假日日历对节前 3 日窗口内全部网点
 * 做峰值预测，超出钞箱日均阈值 150% 的网点自动生成临时计划。
 * requestId 幂等：同键同参返回首次结果，同键异参 409。
 */
public record GenerateTemporaryRequest(
        @NotBlank String requestId
) {
}
