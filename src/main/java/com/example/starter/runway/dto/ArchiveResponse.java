package com.example.starter.runway.dto;

/**
 * 归档响应。归档为终态，重复归档返回幂等成功。
 *
 * @param eventId         事件ID
 * @param status          事件状态（ARCHIVED）
 * @param alreadyArchived 本次调用前是否已处于归档态（重复归档为真）
 */
public record ArchiveResponse(
        long eventId,
        String status,
        boolean alreadyArchived) {
}
