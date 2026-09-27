package com.example.starter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.testsupport.TestClockConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 通过真实 HTTP 入口（MockMvc + H2）验证端到端主流程：
 * 派工 → 力矩 → 自检互检 → H 点缺席等待 → 到场 → 放行归档 → 迟到测量，
 * 以及同键异参 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class OperationHttpFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM acquisition_packet");
        jdbcTemplate.update("DELETE FROM acquisition_batch");
        jdbcTemplate.update("DELETE FROM acquisition_session_point");
        jdbcTemplate.update("DELETE FROM acquisition_session");
        jdbcTemplate.update("DELETE FROM late_measurement_event");
        jdbcTemplate.update("DELETE FROM release_record");
        jdbcTemplate.update("DELETE FROM witness_record");
        jdbcTemplate.update("DELETE FROM inspection_record");
        jdbcTemplate.update("DELETE FROM rework_record");
        jdbcTemplate.update("DELETE FROM torque_record");
        jdbcTemplate.update("DELETE FROM torque_tool");
        jdbcTemplate.update("DELETE FROM fastener");
        jdbcTemplate.update("DELETE FROM station_guard");
        jdbcTemplate.update("DELETE FROM operation_card");
        jdbcTemplate.update("DELETE FROM idempotent_request");
    }

    @Test
    @DisplayName("HTTP 端到端：H 点卡完整流转，含缺席等待、覆盖拦截、放行与迟到测量")
    void fullFlowOverHttp() throws Exception {
        // 登记当日校验工具
        mockMvc.perform(post("/api/tools").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolCode":"TW-1","calibrationDate":"2026-09-26"}"""))
                .andExpect(status().isOk());

        // 派工 H 点卡
        mockMvc.perform(post("/api/cards/dispatch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-dispatch","cardNo":"HTTP-C1","station":"WS-1",
                                 "productSn":"SN-HTTP-1","witnessPoint":"H","operator":"op-A",
                                 "fasteners":[{"pointCode":"BOLT-1","nominalTorque":100.000}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DISPATCHED"));

        // 力矩恰为上边界 105（+5%）合格
        mockMvc.perform(post("/api/torque/measure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-torque","cardNo":"HTTP-C1","pointCode":"BOLT-1",
                                 "measuredTorque":105.000,"operator":"op-A","toolCode":"TW-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inTolerance").value(true))
                .andExpect(jsonPath("$.cardStatus").value("TIGHTENED"));

        // 同一人双签被拒
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-insp-bad","cardNo":"HTTP-C1",
                                 "selfInspector":"p1","mutualInspector":"p1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SELF_MUTUAL_SAME_PERSON"));

        // 不同人员双签通过
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-insp","cardNo":"HTTP-C1",
                                 "selfInspector":"p1","mutualInspector":"p2"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSPECTED"));

        // H 点未见证，覆盖工序拒绝
        mockMvc.perform(post("/api/coverage").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-cov-blocked","cardNo":"HTTP-C1",
                                 "nextOperation":"SEAL"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("WITNESS_REQUIRED"));

        // 见证缺席 → 等待
        mockMvc.perform(post("/api/witness").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-absent","cardNo":"HTTP-C1","action":"ABSENT"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.witnessStatus").value("WAITING"))
                .andExpect(jsonPath("$.cardStatus").value("WAITING_WITNESS"));

        // 见证到场
        mockMvc.perform(post("/api/witness").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-present","cardNo":"HTTP-C1","action":"PRESENT",
                                 "witnessName":"witness-Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.witnessStatus").value("PRESENT"))
                .andExpect(jsonPath("$.cardStatus").value("WITNESSED"));

        // 放行归档
        mockMvc.perform(post("/api/release").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-release","cardNo":"HTTP-C1","releasedBy":"qa-lead"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // 归档后迟到测量：只记迟到事件
        mockMvc.perform(post("/api/torque/measure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-late","cardNo":"HTTP-C1","pointCode":"BOLT-1",
                                 "measuredTorque":300.000,"operator":"op-late","toolCode":"TW-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.cardStatus").value("ARCHIVED"));

        // 复算视图：力矩链 1 条（迟到不进链）、见证链 4 条（缺席+等待+到场）
        mockMvc.perform(get("/api/cards/HTTP-C1/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardStatus").value("ARCHIVED"))
                .andExpect(jsonPath("$.torqueCurve.length()").value(1))
                .andExpect(jsonPath("$.witnessChain.length()").value(3));
    }

    @Test
    @DisplayName("HTTP 端到端：同 requestId 异参返回 409，且不改变工序状态")
    void idempotencyConflictOverHttp() throws Exception {
        String first = """
                {"requestId":"dup-key","cardNo":"HTTP-C2","station":"WS-2",
                 "productSn":"SN-2","witnessPoint":"N","operator":"op-A",
                 "fasteners":[{"pointCode":"B1","nominalTorque":100.000}]}""";
        mockMvc.perform(post("/api/cards/dispatch").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated());

        String different = """
                {"requestId":"dup-key","cardNo":"HTTP-C2-CHANGED","station":"WS-2",
                 "productSn":"SN-2","witnessPoint":"N","operator":"op-A",
                 "fasteners":[{"pointCode":"B1","nominalTorque":100.000}]}""";
        mockMvc.perform(post("/api/cards/dispatch").contentType(MediaType.APPLICATION_JSON).content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"));

        mockMvc.perform(get("/api/cards/HTTP-C2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"));
    }

    @Test
    @DisplayName("HTTP 端到端：逾期工具直接拒绝测量")
    void expiredToolOverHttp() throws Exception {
        mockMvc.perform(post("/api/tools").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolCode":"OLD","calibrationDate":"2026-09-25"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cards/dispatch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d-old","cardNo":"HTTP-C3","station":"WS-3",
                                 "productSn":"SN-3","witnessPoint":"N","operator":"op-A",
                                 "fasteners":[{"pointCode":"B1","nominalTorque":100.000}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/torque/measure").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-old","cardNo":"HTTP-C3","pointCode":"B1",
                                 "measuredTorque":100.000,"operator":"op-A","toolCode":"OLD"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TOOL_CALIBRATION_EXPIRED"));
    }
}
