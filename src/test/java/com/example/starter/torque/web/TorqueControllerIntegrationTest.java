package com.example.starter.torque.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.torque.ServiceClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 层端到端测试：真实 Spring MVC + H2，校验状态码、错误码与完整流转。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TorqueControllerIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ServiceClock clock;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM late_measurement");
        jdbc.update("DELETE FROM witness_event");
        jdbc.update("DELETE FROM inspection_sign");
        jdbc.update("DELETE FROM rework_record");
        jdbc.update("DELETE FROM tightening_record");
        jdbc.update("DELETE FROM fastener_spec");
        jdbc.update("DELETE FROM station_active_card");
        jdbc.update("DELETE FROM work_card");
        jdbc.update("DELETE FROM torque_tool");
        clock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
    }

    @Test
    void fullHappyFlowThroughRelease() throws Exception {
        registerTool("2026-09-26");
        dispatch("C1", "ST-1", true);
        tighten("C1", "100", "t1");

        // 自检/互检不同人员。
        mockMvc.perform(post("/api/cards/C1/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"s1","signType":"SELF","inspector":"alice"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardStatus").value("MUTUAL_CHECK"));
        mockMvc.perform(post("/api/cards/C1/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m1","signType":"MUTUAL","inspector":"bob"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardStatus").value("WITNESS_PENDING"));

        // H 点见证到场。
        mockMvc.perform(post("/api/cards/C1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w1","pointCode":"P1","present":true,"witness":"carol"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("WITNESSED"))
                .andExpect(jsonPath("$.cardStatus").value("COVERING"));

        // 放行归档。
        mockMvc.perform(post("/api/cards/C1/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        mockMvc.perform(get("/api/cards/C1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mockMvc.perform(get("/api/cards/C1/chain/tightening"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void sameInspectorDualSignReturns422() throws Exception {
        registerTool("2026-09-26");
        dispatch("C2", "ST-2", false);
        tighten("C2", "100", "t2");
        mockMvc.perform(post("/api/cards/C2/inspections")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"s2","signType":"SELF","inspector":"alice"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cards/C2/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m2","signType":"MUTUAL","inspector":"alice"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_INSPECTOR"));
    }

    @Test
    void expiredToolReturns422AndConflictOnIdempotencyMismatch() throws Exception {
        registerTool("2026-09-25");
        dispatch("C3", "ST-3", false);

        // 逾期工具：422 TOOL_EXPIRED。
        mockMvc.perform(post("/api/cards/C3/tighten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tt","pointCode":"P1","measuredTorque":100,"toolNo":"TW-1","operator":"op"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TOOL_EXPIRED"));

        registerTool("2026-09-26");
        mockMvc.perform(post("/api/cards/C3/tighten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"k9","pointCode":"P1","measuredTorque":100,"toolNo":"TW-1","operator":"op"}"""))
                .andExpect(status().isOk());
        // 同键异参：409。
        mockMvc.perform(post("/api/cards/C3/tighten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"k9","pointCode":"P1","measuredTorque":90,"toolNo":"TW-1","operator":"op"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void concurrentStationSecondDispatchReturns409() throws Exception {
        dispatch("C4", "ST-DUP", false);
        mockMvc.perform(post("/api/cards/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d5","cardNo":"C5","station":"ST-DUP","productNo":"X",
                                 "fasteners":[{"pointCode":"P1","nominalTorque":100,"hPoint":false}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATION_OCCUPIED"));
    }

    @Test
    void validationErrorReturns400() throws Exception {
        mockMvc.perform(post("/api/cards/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","cardNo":"C6","station":"S","productNo":"P","fasteners":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unknownCardReturns404() throws Exception {
        mockMvc.perform(get("/api/cards/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private void registerTool(String date) throws Exception {
        mockMvc.perform(post("/api/cards/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolNo\":\"TW-1\",\"lastCheckUtcDate\":\"" + date + "\"}"))
                .andExpect(status().isOk());
    }

    private void dispatch(String cardNo, String station, boolean hPoint) throws Exception {
        String body = """
                {"requestId":"d-%s","cardNo":"%s","station":"%s","productNo":"BATCH",
                 "fasteners":[{"pointCode":"P1","nominalTorque":100,"hPoint":%s}]}"""
                .formatted(cardNo, cardNo, station, hPoint);
        mockMvc.perform(post("/api/cards/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void tighten(String cardNo, String torque, String reqId) throws Exception {
        String body = """
                {"requestId":"%s","pointCode":"P1","measuredTorque":%s,"toolNo":"TW-1","operator":"op"}"""
                .formatted(reqId, torque);
        mockMvc.perform(post("/api/cards/" + cardNo + "/tighten")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
