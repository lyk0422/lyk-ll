package com.example.starter.compliance.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * 可控时钟：默认使用系统 UTC 时间；测试可设置固定时刻以验证 UTC 日界与复审期限。
 */
@Component
public class ComplianceClock {

    private volatile Instant fixed;

    public void setFixed(Instant instant) {
        this.fixed = instant;
    }

    public void reset() {
        this.fixed = null;
    }

    public Instant now() {
        Instant value = fixed;
        return value != null ? value : Instant.now();
    }

    public long nowMillis() {
        return now().toEpochMilli();
    }

    /** 当前 UTC 自然日序号（epochDay），复审 90 日期限按此日界计算。 */
    public long todayEpochDay() {
        return LocalDate.ofInstant(now(), ZoneOffset.UTC).toEpochDay();
    }
}
