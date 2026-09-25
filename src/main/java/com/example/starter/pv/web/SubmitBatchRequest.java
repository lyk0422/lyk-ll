package com.example.starter.pv.web;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * 监管递交批次请求。
 *
 * @param requestId 客户端幂等键
 * @param caseNos 指定纳入批次的个例编号；为空时快照提交时刻全部活动个例
 */
public record SubmitBatchRequest(
        @NotBlank String requestId,
        List<String> caseNos) {
}
