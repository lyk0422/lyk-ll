package com.example.starter.dispatch.repo;

/**
 * dispatch_notification 表行记录。
 */
public record NotificationRow(
        long id,
        String requestId,
        String type,
        String message,
        long createdAt) {
}
