package com.example.starter.runway.dto;

import java.time.Instant;

/**
 * 航行通告视图（合成）。
 *
 * @param id          通告记录ID
 * @param eventId     事件ID
 * @param action      CLOSE / REOPEN
 * @param noticeNo    通告编号
 * @param publishedAt 发布时间（UTC）
 */
public record NotamView(
        long id,
        long eventId,
        String action,
        String noticeNo,
        Instant publishedAt) {
}
