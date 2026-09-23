package com.example.starter.costmap.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 障碍物更新请求：以 requestId 幂等，按来源优先级/时间戳/来源 ID 确定性合并。
 *
 * @param requestId  幂等请求 ID（同地图内唯一；同参重放不重复应用，异参返回 409）
 * @param sourceId   来源 ID（传感器/上报方标识）
 * @param obstacleId 来源内障碍物身份标识（同一障碍物的新版本上报使用相同 ID）
 * @param priority   来源公开优先级，数值越大优先级越高
 * @param kind       OCCUPY 或 CLEAR
 * @param region     影响矩形区域（含边界）
 * @param eventTime  来源观测时间戳（epoch 毫秒）；可空表示取服务端当前时刻
 * @param ttlMillis  存活时长（毫秒）；从 eventTime 起算，到期后仅该障碍物版本可被清理
 */
public record ObstacleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String sourceId,
        @NotBlank @Size(max = 64) String obstacleId,
        int priority,
        @NotNull String kind,
        @NotNull @Valid RegionDto region,
        Long eventTime,
        @Min(0) long ttlMillis) {
}
