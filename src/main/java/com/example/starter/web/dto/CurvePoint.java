package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 执行曲线点：按采集时刻归位后的回报读数。
 */
public record CurvePoint(long turbineId, String turbineCode, LocalDateTime reportTime,
                         int actualKw, boolean late) {
}
