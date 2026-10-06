package com.example.starter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.domain.Interval;

/**
 * 引航员工时规则单元测试：连续作业恰满 4 小时判定、单日累计恰 10 小时合法。
 */
class WorkHourValidatorTest {

    private final WorkHourValidator validator = new WorkHourValidator();

    private static Interval iv(int day, int startHour, int startMinute, int endHour, int endMinute) {
        return new Interval(
                LocalDateTime.of(2026, 10, day, startHour, startMinute),
                LocalDateTime.of(2026, 10, endHour < startHour ? day + 1 : day, endHour, endMinute));
    }

    @Test
    void 连续作业恰满4小时合法() {
        List<String> violations = validator.validate(List.of(), iv(6, 8, 0, 12, 0));
        assertTrue(violations.isEmpty(), "恰满 4 小时不应违规: " + violations);
    }

    @Test
    void 连续作业超过4小时且休息不足1小时违规() {
        // 已作业 4 小时，仅休息 30 分钟后再作业 1 小时 -> 同一段连续作业累计 5 小时
        List<String> violations = validator.validate(
                List.of(iv(6, 8, 0, 12, 0)), iv(6, 12, 30, 13, 30));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("连续作业"));
    }

    @Test
    void 连续作业4小时后休息满1小时可继续作业() {
        List<String> violations = validator.validate(
                List.of(iv(6, 8, 0, 12, 0)), iv(6, 13, 0, 15, 0));
        assertTrue(violations.isEmpty(), "休息满 1 小时后应可继续作业: " + violations);
    }

    @Test
    void 分段作业间隔不足1小时累计恰满4小时合法() {
        // 2 小时 + 30 分钟间隔 + 2 小时 = 连续段累计恰 4 小时
        List<String> violations = validator.validate(
                List.of(iv(6, 8, 0, 10, 0)), iv(6, 10, 30, 12, 30));
        assertTrue(violations.isEmpty(), "连续段累计恰 4 小时不应违规: " + violations);
    }

    @Test
    void 分段作业累计超过4小时哪怕1分钟也违规() {
        List<String> violations = validator.validate(
                List.of(iv(6, 8, 0, 10, 0), iv(6, 10, 30, 12, 30)), iv(6, 13, 0, 13, 1));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("连续作业"));
    }

    @Test
    void 单日累计恰10小时合法() {
        // 4h + 休息1h + 4h + 休息1h + 2h = 恰 10 小时
        List<String> violations = validator.validate(
                List.of(iv(6, 0, 0, 4, 0), iv(6, 5, 0, 9, 0)), iv(6, 10, 0, 12, 0));
        assertTrue(violations.isEmpty(), "单日恰 10 小时不应违规: " + violations);
    }

    @Test
    void 单日累计超过10小时哪怕1分钟也违规() {
        List<String> violations = validator.validate(
                List.of(iv(6, 0, 0, 4, 0), iv(6, 5, 0, 9, 0), iv(6, 10, 0, 12, 0)),
                iv(6, 13, 0, 13, 1));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("单日累计"));
    }

    @Test
    void 跨天作业按自然日分别累计() {
        // 22:00-次日02:00：当日 2 小时 + 次日 2 小时，均不超限
        Interval crossMidnight = new Interval(
                LocalDateTime.of(2026, 10, 6, 22, 0), LocalDateTime.of(2026, 10, 7, 2, 0));
        List<String> violations = validator.validate(List.of(), crossMidnight);
        assertTrue(violations.isEmpty(), "跨天 4 小时应按自然日切分: " + violations);
    }
}
