package com.example.starter.inspection;

import jakarta.validation.constraints.NotBlank;

/**
 * 抽样请求。requestId 为幂等键；同一批次同一项目只允许一条抽样记录。
 */
public record SampleRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "检测项目不能为空") String item) {
}
