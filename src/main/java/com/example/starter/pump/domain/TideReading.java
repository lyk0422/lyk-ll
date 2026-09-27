package com.example.starter.pump.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分区潮位数据：按分区+UTC采集时刻唯一，非负。
 *
 * @param id          潮位数据ID
 * @param zoneCode    流域分区代码
 * @param readingTime 潮位采集时刻（UTC）
 * @param level       潮位（米），非负
 * @param late        是否迟到证据：true 只留证据不让工况倒退，false 正常参与判定
 * @param receivedAt  服务接收时刻（Asia/Shanghai）
 */
public record TideReading(
        Long id,
        String zoneCode,
        LocalDateTime readingTime,
        BigDecimal level,
        boolean late,
        LocalDateTime receivedAt
) {
}
