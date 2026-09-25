package com.example.starter.runway;

import java.time.Instant;

/**
 * 航行通告记录行：对应 notam_record 表（合成通告）。
 *
 * @param id          通告记录主键
 * @param eventId     所属事件ID
 * @param runwayId    跑道编号
 * @param action      CLOSE / REOPEN
 * @param noticeNo    通告编号
 * @param publishedAt 发布时间（UTC）
 */
public record NotamRecord(
        long id,
        long eventId,
        String runwayId,
        NotamAction action,
        String noticeNo,
        Instant publishedAt) {
}
