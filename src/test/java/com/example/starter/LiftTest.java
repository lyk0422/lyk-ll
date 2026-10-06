package com.example.starter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 解除限功率：全部事件结案、复测无干扰（恰等限值合法、超过拒绝）、双人确认、原子提交。
 */
class LiftTest extends BaseIntegrationTest {

    private static final Instant T = TestClockConfiguration.T0;

    private JsonNode lift(long transmitterId, int actualPowerW, String ops, String monitor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actualPowerW", actualPowerW);
        body.put("measuredAt", T.plusSeconds(7200).toString());
        body.put("recordedBy", "recorder-1");
        body.put("opsConfirmer", ops);
        body.put("monitorConfirmer", monitor);
        MvcResult result = mvc.perform(post("/api/transmitters/{id}/lift", transmitterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode closeEvent(long eventId) throws Exception {
        Map<String, Object> body = Map.of("closedBy", "op-1", "note", "处置完成");
        MvcResult result = mvc.perform(post("/api/events/{id}/close", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private long confirmedEvent() throws Exception {
        long eventId = triggerEvent("98.100", T);
        postInvestigation(eventId, "inv-lift-" + eventId, true, "确认干扰");
        return eventId;
    }

    @Test
    void liftRejectedWhileEventsUnresolved() throws Exception {
        confirmedEvent();
        JsonNode response = lift(1, 3000, "ops-1", "mon-1");
        assertEquals(409, response.get("status").asInt(), "存在未结事件不得解除");
        assertEquals(0, countRows("retest_record"));
    }

    @Test
    void liftRequiresTwoDistinctConfirmers() throws Exception {
        long eventId = confirmedEvent();
        closeEvent(eventId);

        JsonNode response = lift(1, 3000, "same-person", "same-person");
        assertEquals(400, response.get("status").asInt(), "须运行与监测两名不同人员确认");
        assertEquals(0, countRows("retest_record"));
        assertEquals("EXECUTING", jdbc.queryForObject(
                "SELECT status FROM power_directive WHERE transmitter_id = 1", String.class));
    }

    @Test
    void liftRejectsPowerAboveLimit() throws Exception {
        long eventId = confirmedEvent();
        closeEvent(eventId);

        JsonNode response = lift(1, 3001, "ops-1", "mon-1");
        assertEquals(422, response.get("status").asInt(), "实测功率超过限值拒绝确认");
        assertEquals(0, countRows("retest_record"), "拒绝时不落复测记录");
        assertEquals("EXECUTING", jdbc.queryForObject(
                "SELECT status FROM power_directive WHERE transmitter_id = 1", String.class));
    }

    @Test
    void liftAtExactLimitSucceedsAtomically() throws Exception {
        long eventId = confirmedEvent();
        closeEvent(eventId);

        JsonNode response = lift(1, 3000, "ops-1", "mon-1");

        assertEquals("LIFTED", response.get("status").asText(), "恰等限值合法");
        assertTrue(response.get("retestId").asLong() > 0);
        assertEquals(10000, response.get("restoredPowerW").asInt());
        assertEquals(1, countRows("retest_record"), "复测记录与解除原子提交");
        assertEquals(1, countRows("power_directive"));

        MvcResult power = mvc.perform(get("/api/transmitters/{id}/power", 1)).andReturn();
        JsonNode view = om.readTree(power.getResponse().getContentAsString());
        assertEquals("LIFTED", view.get("directive").get("status").asText());
        assertEquals("ops-1", view.get("directive").get("liftOpsConfirmer").asText());
        assertEquals("mon-1", view.get("directive").get("liftMonitorConfirmer").asText());
        JsonNode revisions = view.get("revisions");
        assertEquals("LIFT", revisions.get(revisions.size() - 1).get("changeType").asText());
        assertEquals(10000, revisions.get(revisions.size() - 1).get("limitWAfter").asInt());
    }

    @Test
    void secondLiftRejectedWhenNoExecutingDirective() throws Exception {
        long eventId = confirmedEvent();
        closeEvent(eventId);
        lift(1, 3000, "ops-1", "mon-1");

        JsonNode response = lift(1, 3000, "ops-2", "mon-2");
        assertEquals(409, response.get("status").asInt(), "无执行中指令不可重复解除");
        assertEquals(1, countRows("retest_record"));
    }
}
