package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 24 小时督办规则：事件创建后 24 小时（UTC）内须初查，恰满 24 小时未初查升级督办。
 */
class EscalationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("未满 24 小时不督办，恰满 24 小时仍未初查则升级督办")
    void exactlyTwentyFourHoursEscalates() throws Exception {
        long eventId = createEventViaComplaints("514.250", "esc");

        clock.advance(Duration.ofHours(23).plusMinutes(59));
        mockMvc.perform(post("/api/events/escalation-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalatedEventIds").isEmpty());
        assertThat(jdbc.queryForObject(
                "SELECT escalated FROM interference_event WHERE id = ?",
                Boolean.class, eventId)).isFalse();

        clock.advance(Duration.ofMinutes(1));
        mockMvc.perform(post("/api/events/escalation-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalatedEventIds[0]").value(eventId));
        assertThat(jdbc.queryForObject(
                "SELECT escalated FROM interference_event WHERE id = ?",
                Boolean.class, eventId)).isTrue();

        // 重复扫描不重复升级
        mockMvc.perform(post("/api/events/escalation-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalatedEventIds").isEmpty());
    }

    @Test
    @DisplayName("已初查事件即使超过 24 小时也不升级督办")
    void reviewedEventNotEscalated() throws Exception {
        long eventId = createEventViaComplaints("514.250", "rev");
        reviewEvent(eventId, "rev-review-1", "NOT_INTERFERENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        clock.advance(Duration.ofHours(48));
        mockMvc.perform(post("/api/events/escalation-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalatedEventIds").isEmpty());
    }
}
