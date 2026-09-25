package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实绩回传结果视图。
 *
 * @param contractNo         合同号
 * @param passNo             道次号
 * @param rollingKm          本次实绩里程（公里）；迟到实绩为 null
 * @param accepted           是否作为新实绩入库（重复回传为 false）
 * @param late               是否为冻结后的迟到实绩
 * @param campaignNo         实绩归位到的换辊周期号；迟到为 null
 * @param campaignTotalKm    归位周期累计实绩里程（公里）；迟到为 null
 * @param locked             排程是否已因换辊阈值锁定
 * @param rollChangeRequired 是否系统提示需要换辊
 * @param recordedAt         实绩发生时刻
 */
public record ActualResultView(
        String contractNo,
        Integer passNo,
        BigDecimal rollingKm,
        boolean accepted,
        boolean late,
        Integer campaignNo,
        BigDecimal campaignTotalKm,
        boolean locked,
        boolean rollChangeRequired,
        LocalDateTime recordedAt) {
}
