package com.example.starter.museum.api;

import java.time.Instant;

/** 通用操作结果视图。 */
public record ActionResponse(
        boolean success,
        String action,
        String message,
        Instant at
) {
    public static ActionResponse of(String action, String message, Instant at) {
        return new ActionResponse(true, action, message, at);
    }
}
