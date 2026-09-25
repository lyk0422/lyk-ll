package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 调度请求结果。{@code created=false} 时表示因支撑时长达到/超过 2 小时未派单。
 *
 * @param requestId        幂等键
 * @param created          是否生成调度单
 * @param order            调度单视图；未派单时为 null
 * @param estimatedMinutes 最新读数估算可支撑分钟数
 * @param reason           未派单原因等说明
 */
public record DispatchResponse(
        String requestId,
        boolean created,
        OrderResponse order,
        Integer estimatedMinutes,
        String reason
) {
}
