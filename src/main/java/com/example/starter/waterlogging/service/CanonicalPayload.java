package com.example.starter.waterlogging.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等指纹规范化载荷构造：字段按字典序排列，时间取 ISO-LOCAL，数值取去尾零纯文本，
 * 保证同参请求的指纹字节级稳定。指纹覆盖 requestId 与全部业务字段。
 */
public final class CanonicalPayload {

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Map<String, Object> entries = new TreeMap<>();

    /** 创建构造器并首先写入 requestId。 */
    public static CanonicalPayload of(String requestId) {
        CanonicalPayload payload = new CanonicalPayload();
        payload.entries.put("requestId", requestId);
        return payload;
    }

    /** 追加字符串字段。 */
    public CanonicalPayload put(String key, String value) {
        entries.put(key, value == null ? "" : value);
        return this;
    }

    /** 追加时刻字段（ISO-LOCAL，时区由 Asia/Shanghai 统一解释）。 */
    public CanonicalPayload putTime(String key, LocalDateTime value) {
        entries.put(key, value == null ? "" : ISO_LOCAL.format(value));
        return this;
    }

    /** 追加数值字段（去除尾零，避免 10.00 与 10.0 的表示差异）。 */
    public CanonicalPayload putNumber(String key, BigDecimal value) {
        entries.put(key, value == null ? "" : value.stripTrailingZeros().toPlainString());
        return this;
    }

    /** 规范化后的有序映射。 */
    public Map<String, Object> asMap() {
        return entries;
    }
}
