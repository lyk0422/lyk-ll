package com.example.starter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 最严格限值：同一发射机多个未结事件取最严限值，关闭其中一件不得提前恢复满功率。
 */
class StrictestLimitTest extends BaseIntegrationTest {

    private static final Instant T = TestClockConfiguration.T0;

    private JsonNode powerView(long transmitterId) throws Exception {
        MvcResult result = mvc.perform(get("/api/transmitters/{id}/power", transmitterId)).andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode closeEvent(long eventId, String closedBy) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("closedBy", closedBy);
        body.put("note", "处置完成");
        MvcResult result = mvc.perform(post("/api/events/{id}/close", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void strictestLimitAppliesAcrossOpenEvents() throws Exception {
        long eventA = triggerEvent("98.100", T);
        JsonNode confirmA = postInvestigation(eventA, "inv-a", true, "确认干扰");
        assertEquals(3000, confirmA.get("effectiveLimitW").asInt());
        long directiveId = confirmA.get("directiveId").asLong();

        long eventB = triggerEvent("98.500", T.plusSeconds(3600));
        JsonNode confirmB = postInvestigation(eventB, "inv-b", true, "确认干扰");
        assertEquals(directiveId, confirmB.get("directiveId").asLong(), "同发射机复用同一执行中指令");
        assertEquals(2000, confirmB.get("effectiveLimitW").asInt(), "取最严格限值");
        assertEquals(1, countRows("power_directive"), "同发射机同时刻仅一条执行中指令");

        JsonNode view = powerView(1);
        assertEquals(2000, view.get("directive").get("currentLimitW").asInt());
        assertEquals("TIGHTEN", view.get("revisions").get(1).get("changeType").asText());

        // 关闭较严的事件 B：放宽至剩余事件限值 3000，而非恢复满功率
        closeEvent(eventB, "op-1");
        view = powerView(1);
        assertEquals(3000, view.get("directive").get("currentLimitW").asInt());
        assertEquals("EXECUTING", view.get("directive").get("status").asText());
        assertEquals("RELAX", view.get("revisions").get(2).get("changeType").asText());

        // 关闭事件 A：指令仍执行中，不得提前恢复满功率
        closeEvent(eventA, "op-1");
        view = powerView(1);
        assertEquals("EXECUTING", view.get("directive").get("status").asText());
        assertEquals(3000, view.get("directive").get("currentLimitW").asInt());
        assertEquals(0, view.get("unresolvedEvents").asInt());
    }

    @Test
    void closeOpenEventRejected() throws Exception {
        long eventId = triggerEvent("98.100", T);
        JsonNode response = closeEvent(eventId, "op-1");
        assertEquals(409, response.get("status").asInt(), "未初查事件不可直接结案");
    }
}
