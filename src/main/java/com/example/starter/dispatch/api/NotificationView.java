package com.example.starter.dispatch.api;

import com.example.starter.dispatch.domain.NotificationType;

import java.time.Instant;

/**
 * 通知留痕视图（超时升级/偏差告警）。
 *
 * @param id        通知编号
 * @param requestId 关联调度令
 * @param type      通知类型
 * @param message   通知内容
 * @param createdAt 通知时刻（UTC）
 */
public record NotificationView(
        long id,
        String requestId,
        NotificationType type,
        String message,
        Instant createdAt) {
}
