package com.example.starter.rebate.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 正式申报请求：疑点复核确认与正式申报在同一事务内推进。
 * 列表中的疑点须为“已说明待复核”，且批次内不得残留未解除疑点，否则申报失败并整体回滚。
 *
 * @param requestId      幂等键
 * @param reviewDoubtIds 本次一并复核确认的疑点 ID 列表
 */
public record DeclareRequest(
        @NotBlank String requestId,
        @NotNull List<@NotNull Long> reviewDoubtIds
) {
}
