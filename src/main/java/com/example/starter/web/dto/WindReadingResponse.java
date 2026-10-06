package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 风速数据上报响应。
 *
 * @param readingId      风速记录 id
 * @param stationId      站点 id
 * @param measuredAt     测量时刻（UTC）
 * @param speedMs        风速（m/s）
 * @param deduplicated   是否命中站点与时刻去重（未新写入）
 * @param late           是否迟到数据（只记迟到事件）
 * @param outOfOrder     是否乱序到达（按时刻归位，不参与判定）
 * @param triggeredEvent 本次上报触发的管制事件，未触发为 null
 */
public record WindReadingResponse(long readingId, long stationId, Instant measuredAt, double speedMs,
                                  boolean deduplicated, boolean late, boolean outOfOrder,
                                  TriggeredEventView triggeredEvent) {
}
