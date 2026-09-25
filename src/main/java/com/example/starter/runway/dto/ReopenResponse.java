package com.example.starter.runway.dto;

/**
 * 恢复开放响应：事件视图与同步发布的航行通告。
 */
public record ReopenResponse(
        ClosureEventView event,
        NotamView notam) {
}
