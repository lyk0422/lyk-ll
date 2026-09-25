package com.example.starter.forestry.support;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

/**
 * 可控业务时钟：所有日期判定均使用 UTC 日界。测试可通过 setToday 拨钟，
 * 生产默认取系统当前 UTC 日期。
 */
@Component
public class BusinessClock {

    private volatile LocalDate today = LocalDate.now(ZoneOffset.UTC);

    /**
     * @return 当前业务日期（UTC）
     */
    public LocalDate today() {
        return today;
    }

    /**
     * 拨定业务日期，仅供测试与管理性维护使用。
     */
    public void setToday(LocalDate date) {
        this.today = date;
    }
}
