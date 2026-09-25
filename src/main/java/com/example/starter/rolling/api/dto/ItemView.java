package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 排产块视图。
 */
public record ItemView(
        int seq,
        int campaignNo,
        int seqInCampaign,
        String contractNo,
        Integer widthMm,
        BigDecimal rollingKm,
        boolean warmup,
        boolean locked,
        LocalDateTime startTime,
        LocalDateTime endTime) {
}
