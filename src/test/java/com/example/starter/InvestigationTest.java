package com.example.starter;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 初查处置：确认干扰与限功率指令同事务落库、requestId 幂等。
 */
class InvestigationTest extends BaseIntegrationTest {

    private static final Instant T = TestClockConfiguration.T0;

    @Test
    void confirmCreatesDirectiveInSameTransaction() throws Exception {
        long eventId = triggerEvent("98.100", T);

        JsonNode response = postInvestigation(eventId, "inv-1", true, "确认为同频干扰");

        assertEquals("CONFIRMED", response.get("status").asText());
        assertEquals("CONFIRMED", response.get("conclusion").asText());
        long directiveId = response.get("directiveId").asLong();
        assertTrue(directiveId > 0);
        assertEquals(3000, response.get("effectiveLimitW").asInt(), "限值取自合成保护表");

        MvcResult power = mvc.perform(get("/api/transmitters/{id}/power", 1)).andReturn();
        JsonNode view = om.readTree(power.getResponse().getContentAsString());
        assertEquals("EXECUTING", view.get("directive").get("status").asText());
        assertEquals(3000, view.get("directive").get("currentLimitW").asInt());
        assertEquals("ISSUE", view.get("revisions").get(0).get("changeType").asText());
        assertEquals(1, countRows("power_directive"), "同发射机同时刻仅一条执行中指令");
    }

    @Test
    void confirmIdempotentReplayReturnsFirstResult() throws Exception {
        long eventId = triggerEvent("98.100", T);
        JsonNode first = postInvestigation(eventId, "inv-2", true, "确认干扰");

        JsonNode replay = postInvestigation(eventId, "inv-2", true, "确认干扰");

        assertTrue(replay.get("idempotentReplay").asBoolean());
        assertEquals(first.get("directiveId").asLong(), replay.get("directiveId").asLong());
        assertEquals(first.get("effectiveLimitW").asInt(), replay.get("effectiveLimitW").asInt());
        assertEquals(1, countRows("power_directive"));
        assertEquals(1, countRows("power_directive_revision"), "重放不产生新轨迹");
    }

    @Test
    void confirmSameRequestIdDifferentParamsConflict() throws Exception {
        long eventId = triggerEvent("98.100", T);
        postInvestigation(eventId, "inv-3", true, "确认干扰");

        JsonNode conflict = postInvestigation(eventId, "inv-3", false, "改口排除");

        assertEquals(409, conflict.get("status").asInt());
        assertEquals(1, countRows("power_directive"), "同键异参不改变首次处置结果");
    }

    @Test
    void clearedConclusionClosesEventWithoutDirective() throws Exception {
        long eventId = triggerEvent("98.100", T);

        JsonNode response = postInvestigation(eventId, "inv-4", false, "排查为误报");

        assertEquals("CLOSED", response.get("status").asText());
        assertEquals("CLEARED", response.get("conclusion").asText());
        assertTrue(response.get("directiveId").isNull());
        assertEquals(0, countRows("power_directive"));
    }

    @Test
    void investigateMissingEventReturns404() throws Exception {
        JsonNode response = postInvestigation(9999L, "inv-5", true, "不存在的事件");
        assertEquals(404, response.get("status").asInt());
    }

    @Test
    void secondInvestigationWithDifferentRequestIdRejected() throws Exception {
        long eventId = triggerEvent("98.100", T);
        postInvestigation(eventId, "inv-6", true, "确认干扰");

        JsonNode again = postInvestigation(eventId, "inv-7", true, "重复处置");

        assertEquals(409, again.get("status").asInt());
        assertEquals(1, countRows("power_directive"));
    }
}
