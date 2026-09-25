package com.example.starter.epidemic.service;

import com.example.starter.epidemic.repo.DiseasePo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告时限纯单元测试：甲类 2h、乙类 24h，恰在终点判及时。
 */
class ReportingRulesTest {

    private DiseasePo disease(String category) {
        DiseasePo po = new DiseasePo();
        po.code = "X";
        po.name = "测试病种";
        po.category = category;
        po.enabled = true;
        return po;
    }

    @Test
    void category_a_deadline_is_two_hours() {
        LocalDateTime diagnosis = LocalDateTime.of(2026, 9, 25, 8, 0, 0);
        assertThat(ReportingRules.deadline(disease("A"), diagnosis))
                .isEqualTo(LocalDateTime.of(2026, 9, 25, 10, 0, 0));
    }

    @Test
    void category_b_deadline_is_twenty_four_hours() {
        LocalDateTime diagnosis = LocalDateTime.of(2026, 9, 24, 10, 0, 0);
        assertThat(ReportingRules.deadline(disease("B"), diagnosis))
                .isEqualTo(LocalDateTime.of(2026, 9, 25, 10, 0, 0));
    }

    @Test
    void exactly_at_deadline_is_timely() {
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 25, 10, 0, 0);
        assertThat(ReportingRules.isTimely(deadline, deadline)).isTrue();
    }

    @Test
    void one_second_after_deadline_is_late() {
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 25, 10, 0, 0);
        assertThat(ReportingRules.isTimely(deadline, deadline.plusSeconds(1))).isFalse();
    }

    @Test
    void one_second_before_deadline_is_timely() {
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 25, 10, 0, 0);
        assertThat(ReportingRules.isTimely(deadline, deadline.minusSeconds(1))).isTrue();
    }
}
