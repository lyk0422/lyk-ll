package com.example.starter.rolling.api.dto;

import java.time.LocalDateTime;

/**
 * 换辊窗口视图，半开区间 [startTime, endTime)。
 */
public record WindowView(
        int campaignNo,
        LocalDateTime startTime,
        LocalDateTime endTime) {
}
