package com.example.starter.pv.service;

import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.pv.domain.Seriousness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时限规则纯单元测试：7 日/15 日期限日、最后一日 23:59:59 边界、30 日关闭边界。
 */
class DeadlineRulesTest {

    private final LocalDate t0 = LocalDate.of(2026, 1, 10);

    @Test
    void dueDateFatalAndLifeThreateningIsT0Plus6() {
        assertEquals(LocalDate.of(2026, 1, 16),
                DeadlineRules.dueDate(Seriousness.FATAL, t0));
        assertEquals(LocalDate.of(2026, 1, 16),
                DeadlineRules.dueDate(Seriousness.LIFE_THREATENING, t0));
    }

    @Test
    void dueDateOtherSeriousIsT0Plus14() {
        assertEquals(LocalDate.of(2026, 1, 24),
                DeadlineRules.dueDate(Seriousness.OTHER_SERIOUS, t0));
    }

    @Test
    void submissionAtLastDay235959IsOnTime() {
        LocalDate due = LocalDate.of(2026, 1, 16);
        assertTrue(DeadlineRules.isOnTime(due, Instant.parse("2026-01-16T23:59:59Z")));
    }

    @Test
    void submissionOneSecondAfterDeadlineIsLate() {
        LocalDate due = LocalDate.of(2026, 1, 16);
        assertFalse(DeadlineRules.isOnTime(due, Instant.parse("2026-01-17T00:00:00Z")));
    }

    @Test
    void submissionOnT0Within7And15Days() {
        Instant within7 = Instant.parse("2026-01-10T08:00:00Z");
        assertTrue(DeadlineRules.isOnTime(
                DeadlineRules.dueDate(Seriousness.FATAL, t0), within7));
        assertTrue(DeadlineRules.isOnTime(
                DeadlineRules.dueDate(Seriousness.OTHER_SERIOUS, t0), within7));
    }

    @Test
    void closeExactly30DaysAfterLastInfoIsAllowed() {
        LocalDate lastInfo = LocalDate.of(2026, 3, 1);
        assertEquals(LocalDate.of(2026, 3, 31),
                DeadlineRules.closeEarliestDate(lastInfo));
        assertFalse(DeadlineRules.isCloseable(lastInfo, LocalDate.of(2026, 3, 30)));
        assertTrue(DeadlineRules.isCloseable(lastInfo, LocalDate.of(2026, 3, 31)));
    }
}
