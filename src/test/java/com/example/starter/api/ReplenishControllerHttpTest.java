package com.example.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.starter.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 经真实 HTTP 入口的端到端集成测试（H2 MySQL 模式）：覆盖编制→审批→双人押运→清点→
 * 三方核销→封存主流程，以及 409 冲突、同键重放与迟到事件。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfig.class})
@AutoConfigureMockMvc
class ReplenishControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM plan_discrepancy");
        jdbc.update("DELETE FROM escort_checkin");
        jdbc.update("DELETE FROM fast_track_decision");
        jdbc.update("DELETE FROM replenish_plan");
        jdbc.update("UPDATE atm SET frozen = 0");
        jdbc.update("UPDATE custodian SET frozen = 0");
    }

    private Map<String, Object> body(String requestId, Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", requestId);
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private MvcResult postJson(String url, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    @Test
    void full_happy_path_over_http_with_replay_and_seal() throws Exception {
        // 1. 预测编制（显式金额，恰等容量）
        MvcResult created = postJson("/api/plans", body("rid-create", "atmCode", "ATM-0001", "amount", 150000));
        long planId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // 同键同参重放：200 且同一单号，不重复建单
        mockMvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-create", "atmCode", "ATM-0001", "amount", 150000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId));

        // 同键异参：409
        mockMvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-create", "atmCode", "ATM-0001", "amount", 150001))))
                .andExpect(status().isConflict());

        // 2. 审批
        mockMvc.perform(post("/api/plans/" + planId + "/approve").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-approve"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.escortDeadline").exists());

        // 3. 双人押运
        mockMvc.perform(post("/api/plans/" + planId + "/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-c1", "custodianCode", "C-01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.checkinCount").value(1));
        mockMvc.perform(post("/api/plans/" + planId + "/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-c2", "custodianCode", "C-02"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ESCORTED"))
                .andExpect(jsonPath("$.checkinCount").value(2));

        // 4. 清点
        mockMvc.perform(post("/api/plans/" + planId + "/count").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                body("rid-count", "countedAmount", 150000, "custodianCode", "C-01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COUNTED"));

        // 5. 设备确认，三方一致核销
        mockMvc.perform(post("/api/plans/" + planId + "/device-confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-device", "deviceAmount", 150000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECONCILED"));

        // 三方对账复算一致
        mockMvc.perform(get("/api/plans/" + planId + "/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));

        // 6. 封存终态，重复封存幂等
        mockMvc.perform(post("/api/plans/" + planId + "/seal").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-seal"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
        mockMvc.perform(post("/api/plans/" + planId + "/seal").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-seal"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));

        // 封存后迟到清点：200、late=true、状态不变
        mockMvc.perform(post("/api/plans/" + planId + "/count").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                body("rid-late", "countedAmount", 1, "custodianCode", "C-02"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.status").value("SEALED"));

        // 台账可复算
        mockMvc.perform(get("/api/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(planId));
    }

    @Test
    void unknown_plan_returns_404_and_over_capacity_returns_409() throws Exception {
        mockMvc.perform(get("/api/plans/9999")).andExpect(status().isNotFound());

        mockMvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                body("rid-over", "atmCode", "ATM-0002", "amount", 600001))))
                .andExpect(status().isConflict());
    }

    @Test
    void temporary_generate_over_http_and_daily_reconciliation() throws Exception {
        // 测试时钟固定在 2026-09-20，位于合成节假日 2026-09-23 前 3 日窗口内
        MvcResult generated = mockMvc.perform(post("/api/plans/temporary/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-temp-gen"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preHolidayWindow").value(true))
                .andExpect(jsonPath("$.generatedCount").value(3))
                .andExpect(jsonPath("$.plans[0].planType").value("TEMPORARY"))
                .andExpect(jsonPath("$.plans[0].status").value("APPROVED"))
                .andReturn();
        long tempId = objectMapper.readTree(generated.getResponse().getContentAsString())
                .get("plans").get(0).get("id").asLong();

        // 同键重放返回首次结果，不重复生成
        mockMvc.perform(post("/api/plans/temporary/generate").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-temp-gen"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedCount").value(3));

        // 临时计划押运时限 2 小时：08:00 批准，截止 10:00
        mockMvc.perform(get("/api/plans/" + tempId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escortDeadline").value("2026-09-20T10:00:00Z"));

        // 快速通道结论纳入日结核对
        mockMvc.perform(get("/api/plans/daily-reconciliation/2026-09-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fastTrackDecisionCount").value(3))
                .andExpect(jsonPath("$.fastTrackDecisions[0].decision").value("AUTO_APPROVED"));
    }
}
