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

/**
 * 通过真实 HTTP 入口（MockMvc + H2）验证本地采集会话：
 * 建会话 → 先缓冲序号 2 → 查水位线为 0 → 序号 1 到齐前缀入账 → 关闭。
 * 并覆盖参数非法 400、会话不存在 404、整批未知点 422。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class CollectionHttpFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM late_measurement_event");
        jdbcTemplate.update("DELETE FROM release_record");
        jdbcTemplate.update("DELETE FROM witness_record");
        jdbcTemplate.update("DELETE FROM inspection_record");
        jdbcTemplate.update("DELETE FROM rework_record");
        jdbcTemplate.update("DELETE FROM torque_record");
        jdbcTemplate.update("DELETE FROM collection_packet");
        jdbcTemplate.update("DELETE FROM collection_session_point");
        jdbcTemplate.update("DELETE FROM collection_session");
        jdbcTemplate.update("DELETE FROM torque_tool");
        jdbcTemplate.update("DELETE FROM fastener");
        jdbcTemplate.update("DELETE FROM station_guard");
        jdbcTemplate.update("DELETE FROM operation_card");
        jdbcTemplate.update("DELETE FROM idempotent_request");
    }

    @Test
    @DisplayName("HTTP 端到端：乱序缓冲、水位线查询、前缀入账与关闭")
    void bufferedThenDrainOverHttp() throws Exception {
        mockMvc.perform(post("/api/tools").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolCode":"TW-9","calibrationDate":"2026-09-26"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cards/dispatch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-d","cardNo":"HTTP-CC","station":"WS-9",
                                 "productSn":"SN-CC","witnessPoint":"N","operator":"op-A",
                                 "fasteners":[{"pointCode":"B1","nominalTorque":100.000}]}"""))
                .andExpect(status().isCreated());

        // 建会话：版本 1、水位线 0。
        mockMvc.perform(post("/api/collection/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-open","expectedVersion":1,"sessionNo":"S-HTTP",
                                 "cardNo":"HTTP-CC","toolCode":"TW-9","expectedCount":2,"pointCodes":["B1"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.watermark").value(0))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 序号 2 先到：缓冲，水位线仍为 0。
        mockMvc.perform(post("/api/collection/sessions/S-HTTP/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-b2","sessionNo":"S-HTTP","expectedVersion":1,
                                 "packets":[{"seq":2,"pointCode":"B1","measuredTorque":100.000,
                                             "measuredAt":"2026-09-26T08:02:00Z"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermarkAfter").value(0))
                .andExpect(jsonPath("$.bufferedSeqs[0]").value(2));

        mockMvc.perform(get("/api/collection/sessions/S-HTTP/watermark"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(0))
                .andExpect(jsonPath("$.nextExpectedSeq").value(1))
                .andExpect(jsonPath("$.bufferedSeqs[0]").value(2));

        // 序号 1 到齐：1、2 一并入账，水位线推进到 2。
        mockMvc.perform(post("/api/collection/sessions/S-HTTP/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-b1","sessionNo":"S-HTTP","expectedVersion":2,
                                 "packets":[{"seq":1,"pointCode":"B1","measuredTorque":100.000,
                                             "measuredAt":"2026-09-26T08:01:00Z"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermarkAfter").value(2))
                .andExpect(jsonPath("$.admittedSeqs[0]").value(1))
                .andExpect(jsonPath("$.admittedSeqs[1]").value(2));

        // 包证据：全部 ADMITTED，attempt 分别 1、2。
        mockMvc.perform(get("/api/collection/sessions/S-HTTP/packets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("ADMITTED"))
                .andExpect(jsonPath("$[0].attemptNo").value(1))
                .andExpect(jsonPath("$[1].state").value("ADMITTED"))
                .andExpect(jsonPath("$[1].attemptNo").value(2));

        mockMvc.perform(post("/api/collection/sessions/S-HTTP/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-close","sessionNo":"S-HTTP","expectedVersion":3}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    @DisplayName("参数非法 400（空包列表/超 20 包/非正力矩）；会话不存在 404；未知点整批 422")
    void validationAndNotFoundOverHttp() throws Exception {
        mockMvc.perform(post("/api/tools").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolCode":"TW-9","calibrationDate":"2026-09-26"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cards/dispatch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-d2","cardNo":"HTTP-CC2","station":"WS-92",
                                 "productSn":"SN-CC2","witnessPoint":"N","operator":"op-A",
                                 "fasteners":[{"pointCode":"B1","nominalTorque":100.000}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/collection/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-open2","expectedVersion":1,"sessionNo":"S-HTTP2",
                                 "cardNo":"HTTP-CC2","toolCode":"TW-9","expectedCount":2,"pointCodes":["B1"]}"""))
                .andExpect(status().isCreated());

        // 非正力矩 → 400。
        mockMvc.perform(post("/api/collection/sessions/S-HTTP2/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-bad","sessionNo":"S-HTTP2","expectedVersion":1,
                                 "packets":[{"seq":1,"pointCode":"B1","measuredTorque":0,
                                             "measuredAt":"2026-09-26T08:01:00Z"}]}"""))
                .andExpect(status().isBadRequest());

        // 未知会话 → 404。
        mockMvc.perform(get("/api/collection/sessions/NO-SUCH/watermark"))
                .andExpect(status().isNotFound());

        // 未知点整批 422。
        mockMvc.perform(post("/api/collection/sessions/S-HTTP2/packets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-unknown","sessionNo":"S-HTTP2","expectedVersion":1,
                                 "packets":[{"seq":1,"pointCode":"ZZ","measuredTorque":100.000,
                                             "measuredAt":"2026-09-26T08:01:00Z"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_POINT"));
    }
}
