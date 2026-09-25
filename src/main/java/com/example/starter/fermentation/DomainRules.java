package com.example.starter.fermentation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * 发酵工艺判定口径常量与阈值比较：所有边界值均按“恰界合格/触发”实现，可复算。
 */
public final class DomainRules {

    /** 主酵温度允许偏离目标的阈值（℃），恰 0.5℃ 合格。 */
    public static final BigDecimal TEMPERATURE_TOLERANCE = new BigDecimal("0.50");

    /** 连续偏离采样点数达到该值即告警，恰连续 3 点触发。 */
    public static final int ALARM_CONSECUTIVE_POINTS = 3;

    /** 双乙酰合格上限（mg/L），恰 0.10 合格。 */
    public static final BigDecimal DIACETYL_LIMIT = new BigDecimal("0.100");

    /** 连续两次合格双乙酰采样的最小间隔，恰 12 小时合法。 */
    public static final Duration DIACETYL_MIN_INTERVAL = Duration.ofHours(12);

    /** 后熟保温目标温度（℃）。 */
    public static final BigDecimal MATURITY_TARGET_TEMP = new BigDecimal("-1.00");

    /** 后熟最少保温天数，恰 7 天合法。 */
    public static final Duration MATURITY_MIN_HOLD = Duration.ofDays(7);

    private DomainRules() {
    }

    /**
     * 实测温度是否超出目标 ±0.5℃：偏差绝对值恰 0.5℃ 时返回 false（合格）。
     */
    public static boolean isTemperatureOutOfRange(BigDecimal target, BigDecimal measured) {
        return measured.subtract(target).abs().compareTo(TEMPERATURE_TOLERANCE) > 0;
    }

    /**
     * 双乙酰含量是否合格：恰 0.10mg/L 合格。
     */
    public static boolean isDiacetylPassing(BigDecimal valueMgL) {
        return valueMgL.compareTo(DIACETYL_LIMIT) <= 0;
    }

    /**
     * 两次采样间隔是否达到最短要求：恰 12 小时合法。
     */
    public static boolean meetsSamplingInterval(Duration interval) {
        return interval.compareTo(DIACETYL_MIN_INTERVAL) >= 0;
    }

    /**
     * 后熟保持时长是否达标：恰 7 天合法。
     */
    public static boolean meetsMaturityHold(Duration holdDuration) {
        return holdDuration.compareTo(MATURITY_MIN_HOLD) >= 0;
    }

    /**
     * 计算覆盖 requestId 与全部业务字段的幂等指纹（SHA-256，十六进制）。
     */
    public static String sha256(String canonicalPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
