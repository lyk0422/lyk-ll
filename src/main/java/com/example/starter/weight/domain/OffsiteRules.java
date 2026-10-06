package com.example.starter.weight.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 非现场取证规则常量与纯函数。
 *
 * <p>去重窗口：非现场数据与现场记录按车牌与过磅时刻窗口去重，
 * 窗口内（±5 分钟，含边界）重复数据只保留首次。
 */
public final class OffsiteRules {

    /**
     * 非现场与现场记录的去重时刻窗口（±5 分钟）。
     */
    public static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);

    /**
     * 非现场案件在案件表中使用的虚拟检测站编码（非现场无实体检测站）。
     */
    public static final String OFFSITE_STATION = "OFFSITE";

    private OffsiteRules() {
    }

    /**
     * 判断过磅时刻是否落在设备校准有效期内（含起止边界）。
     *
     * @param weighTime       过磅时刻（UTC）
     * @param calibratedFrom  校准有效期起（UTC，含）
     * @param calibratedUntil 校准有效期止（UTC，含）
     * @return true 表示在有效期内，可进入证据链
     */
    public static boolean withinCalibration(Instant weighTime, Instant calibratedFrom, Instant calibratedUntil) {
        return !weighTime.isBefore(calibratedFrom) && !weighTime.isAfter(calibratedUntil);
    }
}
