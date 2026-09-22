package com.example.starter.costmap.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 障碍物更新请求。存活条件：当前时刻 now &lt;= timestampMs + ttlMs。
 *
 * @param requestId 客户端幂等请求ID；同参重放不重复应用，异参返回 409
 * @param sourceId 障碍物来源ID（同格合并决胜第三键）
 * @param priority 来源优先级，越大越优先（决胜第一键），缺省 0
 * @param xMin 区域最小列（含）
 * @param yMin 区域最小行（含）
 * @param xMax 区域最大列（含）
 * @param yMax 区域最大行（含）
 * @param timestampMs 障碍物事件时间戳 epoch 毫秒，缺省取服务时钟
 * @param ttlMs 存活时长毫秒
 * @param expectedSeq 可选乐观版本约束；与当前 seq 不一致返回 409
 */
public record ObstacleUpdateRequest(
        @NotBlank String requestId,
        @NotBlank String sourceId,
        Integer priority,
        @NotNull @Min(0) Integer xMin,
        @NotNull @Min(0) Integer yMin,
        @NotNull @Min(0) Integer xMax,
        @NotNull @Min(0) Integer yMax,
        Long timestampMs,
        @NotNull @Min(0) Long ttlMs,
        Long expectedSeq) {
}
