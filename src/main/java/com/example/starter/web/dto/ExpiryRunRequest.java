package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 到期巡检请求。
 *
 * @param now 判定时刻（UTC）；为空取当前时刻。恰满到期日 24:00（UTC 日界）失效
 */
public record ExpiryRunRequest(Instant now) {

    /**
     * 到期巡检结果。
     *
     * @param stoppedRouteIds   本次停运的线路 ID
     * @param reassignmentCount 生成的改派记录数
     * @param notificationCount 生成的通知记录数
     */
    public record Result(List<Long> stoppedRouteIds, int reassignmentCount, int notificationCount) {
    }
}
