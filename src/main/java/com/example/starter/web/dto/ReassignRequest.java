package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 整体改派请求：旧单已到场车保留在新方案，其余释放并换为 newTruckIds 中的新车，全方案原子切换。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的旧调度单版本
 * @param newTruckIds     换入的新车ID列表（可为空，空表示仅保留已到场车）
 */
public record ReassignRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull List<@NotNull Long> newTruckIds) {
}
