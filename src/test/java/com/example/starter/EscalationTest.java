package com.example.starter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 24 小时督办：事件创建后恰满 24 小时（UTC）仍未初查则升级督办。
 */
class EscalationTest extends BaseIntegrationTest {

    private static final Instant T = TestClockConfiguration.T0;

    private JsonNode getEvent(long eventId) throws Exception {
        MvcResult result = mvc.perform(get("/api/events/{id}", eventId)).andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void escalatedExactlyAt24Hours() throws Exception {
        long eventId = triggerEvent("98.100", T);

        clock.setInstant(T.plus(23, ChronoUnit.HOURS).plus(59, ChronoUnit.MINUTES));
        assertFalse(getEvent(eventId).get("escalated").asBoolean(), "未满 24 小时不督办");

        clock.setInstant(T.plus(24, ChronoUnit.HOURS));
        assertTrue(getEvent(eventId).get("escalated").asBoolean(), "恰满 24 小时未初查升级督办");
    }

    @Test
    void concludedEventNeverEscalates() throws Exception {
        long eventId = triggerEvent("98.100", T);
        clock.setInstant(T.plus(1, ChronoUnit.HOURS));
        postInvestigation(eventId, "inv-esc-1", false, "排查为非干扰");

        clock.setInstant(T.plus(48, ChronoUnit.HOURS));
        assertFalse(getEvent(eventId).get("escalated").asBoolean(), "已初查事件不督办");
    }
}
