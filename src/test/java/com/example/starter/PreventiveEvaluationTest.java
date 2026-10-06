package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * 相邻库区 90 日内连续 2 次虫情触发整体预防性消杀评估，恰第 2 次触发。
 */
class PreventiveEvaluationTest extends BaseIntegrationTest {

    @Test
    void secondPestInAdjacentClusterTriggersExactlyOnce() throws Exception {
        reportCase("A", "rpt-A");
        assertEquals(0, evaluationCount());

        // 相邻组团内第 2 次虫情：触发评估
        reportCase("B", "rpt-B");
        assertEquals(1, evaluationCount());

        // 恰第 2 次触发：组团内第 3 次不再新增评估
        reportCase("C", "rpt-C");
        assertEquals(1, evaluationCount());

        mockMvc.perform(get("/api/preventive-evaluations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].zoneCodes").value("A,B,C"));
    }

    @Test
    void nonAdjacentZoneDoesNotTrigger() throws Exception {
        reportCase("A", "rpt-A");
        // D 为独立库区，不属于 A 的相邻组团
        reportCase("D", "rpt-D");
        assertEquals(0, evaluationCount());
    }

    @Test
    void outsideNinetyDayWindowDoesNotTrigger() throws Exception {
        reportCase("A", "rpt-A");

        // 91 天后的虫情超出 90 日窗口，不构成连续
        clock.setInstant(T0.plus(91, ChronoUnit.DAYS));
        reportCase("B", "rpt-B");
        assertEquals(0, evaluationCount());
    }

    private long evaluationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM preventive_evaluation", Long.class);
    }
}
