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
 * 采集协议 HTTP 端到端：创建会话 → 乱序上传 → 补齐入账 → 关闭 → 自检互检 → 放行归档
 * → 迟到包；以及参数校验 400 与同键异参 409。
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
        jdbcTemplate.update("DELETE FROM collection_batch");
        jdbcTemplate.update("DELETE FROM collection_packet");
        jdbcTemplate.update("DELETE FROM collection_session_point");
        jdbcTemplate.update("DELETE FROM collection_session");
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
    @DisplayName("HTTP 端到端：采集会话全流程（乱序缓冲→前缀入账→关闭→归档→迟到包）")
    void collectionFullFlowOverHttp() throws Exception {
        // 登记当日校验工具并派工单点卡
        mockMvc.perform(post("/api/tools").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolCode":"TW-C","calibrationDate":"2026-09-26"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cards/dispatch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hc-d","cardNo":"HTTP-CC1","station":"WS-C1",
                                 "productSn":"SN-CC1","witnessPoint":"N","operator":"op-A",
                                 "fasteners":[{"pointCode":"B1","nominalTorque":100.000}]}"""))
                .andExpect(status().isCreated());

        // 创建采集会话
        mockMvc.perform(post("/api/collection/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hc-cs","sessionNo":"SES-H1","cardNo":"HTTP-CC1",
                                 "toolCode":"TW-C"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.watermark").value(0))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.calibrationDate").value("2026-09-26"))
                .andExpect(jsonPath("$.points[0]").value("B1"));

        // 乱序：2 号包先到，仅缓冲不消费
        mockMvc.perform(post("/api/collection/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hc-u1","sessionNo":"SES-H1","expectedVersion":1,
                                 "packets":[{"seq":2,"pointCode":"B1","measuredTorque":100.000,
                                             "measuredAt":"2026-09-26T01:01:00Z"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumed").value(0))
                .andExpect(jsonPath("$.watermark").value(0));

        // 补齐 1 号：前缀连续入账，卡转 TIGHTENED
        mockMvc.perform(post("/api/collection/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hc-u2","sessionNo":"SES-H1","expectedVersion":2,
                                 "packets":[{"seq":1,"pointCode":"B1","measuredTorque":100.000,
                                             "measuredAt":"2026-09-26T01:00:00Z"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumed").value(2))
                .andExpect(jsonPath("$.watermark").value(2))
                .andExpect(jsonPath("$.cardStatus").value("TIGHTENED"));

        // 会话查询：水位线 2、版本 3、消费 2
        mockMvc.perform(get("/api/collection/sessions/SES-H1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(2))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.consumedCount").value(2));

        // 包证据查询：两条 CONSUMED
        mockMvc.perform(get("/api/collection/sessions/SES-H1/packets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("CONSUMED"))
                .andExpect(jsonPath("$[1].status").value("CONSUMED"));

        // 批次查询：两批
        mockMvc.perform(get("/api/collection/sessions/SES-H1/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 关闭会话
        mockMvc.perform(post("/api/collection/sessions/close").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hc-close","sessionNo":"SES-H1","expectedVersion":3}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.version").value(4));

        // 自检互检 → 放行归档
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hc-insp","cardNo":"HTTP-CC1",
                                 "selfInspector":"p1","mutualInspector":"p2"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/release").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hc-rel","cardNo":"HTTP-CC1","releasedBy":"qa"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // 归档后 3 号包：只记迟到，不复活卡片
        mockMvc.perform(post("/api/collection/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hc-late","sessionNo":"SES-H1","expectedVersion":4,
                                 "packets":[{"seq":3,"pointCode":"B1","measuredTorque":101.000,
                                             "measuredAt":"2026-09-26T01:02:00Z"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.cardStatus").value("ARCHIVED"))
                .andExpect(jsonPath("$.watermark").value(2));
        mockMvc.perform(get("/api/collection/sessions/SES-H1/packets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].status").value("LATE"));
        mockMvc.perform(get("/api/cards/HTTP-CC1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    @DisplayName("HTTP：参数非法 400（空批/缺字段）、会话不存在 404、同键异参 409")
    void collectionErrorSemanticsOverHttp() throws Exception {
        // 空批 → 400（bean 校验）
        mockMvc.perform(post("/api/collection/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-1","sessionNo":"SES-GHOST","expectedVersion":1,
                                 "packets":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // 会话不存在 → 404
        mockMvc.perform(post("/api/collection/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-2","sessionNo":"SES-GHOST","expectedVersion":1,
                                 "packets":[{"seq":1,"pointCode":"B1","measuredTorque":100.000,
                                             "measuredAt":"2026-09-26T01:00:00Z"}]}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"));
        mockMvc.perform(get("/api/collection/sessions/SES-GHOST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"));

        // 同键异参 → 409
        mockMvc.perform(post("/api/tools").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolCode":"TW-E","calibrationDate":"2026-09-26"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cards/dispatch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-d","cardNo":"HTTP-CC2","station":"WS-C2",
                                 "productSn":"SN-CC2","witnessPoint":"N","operator":"op-A",
                                 "fasteners":[{"pointCode":"B1","nominalTorque":100.000}]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/collection/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-cs","sessionNo":"SES-H2","cardNo":"HTTP-CC2",
                                 "toolCode":"TW-E"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/collection/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-cs","sessionNo":"SES-H2-OTHER","cardNo":"HTTP-CC2",
                                 "toolCode":"TW-E"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"));
    }
}
