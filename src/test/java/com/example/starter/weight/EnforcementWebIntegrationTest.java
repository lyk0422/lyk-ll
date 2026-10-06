package com.example.starter.weight;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web 层集成测试：验证过磅、处罚、复检、结案接口的 HTTP 语义与错误码。
 */
@AutoConfigureMockMvc
class EnforcementWebIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String WEIGH_BODY = """
            {"requestId":"%s","plateNo":"沪A10001","stationCode":"ST001",
             "weighTime":"2026-09-01T02:00:00Z","mode":"DYNAMIC","totalKg":40300.00}
            """;

    @Test
    @DisplayName("过磅主流程：超限率 30% 档返回罚款记分分档")
    void weighMainFlow() throws Exception {
        mockMvc.perform(post("/api/enforcement/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WEIGH_BODY.formatted("W-WEB-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overloadRate").value(0.30))
                .andExpect(jsonPath("$.tier").value("FINE_POINTS"))
                .andExpect(jsonPath("$.role").value("AUTHORITATIVE"))
                .andExpect(jsonPath("$.caseNo").isString());
    }

    @Test
    @DisplayName("同键异参重放返回 409")
    void idempotentConflictReturns409() throws Exception {
        mockMvc.perform(post("/api/enforcement/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WEIGH_BODY.formatted("W-WEB-2")))
                .andExpect(status().isOk());

        String conflict = WEIGH_BODY.formatted("W-WEB-2").replace("40300.00", "50000.00");
        mockMvc.perform(post("/api/enforcement/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_CONFLICT"));
    }

    @Test
    @DisplayName("未知车辆返回 404")
    void unknownVehicleReturns404() throws Exception {
        String body = """
                {"requestId":"W-WEB-3","plateNo":"不存在的车牌","stationCode":"ST001",
                 "weighTime":"2026-09-01T02:00:00Z","mode":"DYNAMIC","totalKg":1000.00}
                """;
        mockMvc.perform(post("/api/enforcement/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("参数校验失败返回 400")
    void validationFailureReturns400() throws Exception {
        String body = """
                {"requestId":"","plateNo":"沪A10001","stationCode":"ST001",
                 "weighTime":"2026-09-01T02:00:00Z","mode":"DYNAMIC","totalKg":40300.00}
                """;
        mockMvc.perform(post("/api/enforcement/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("完整链路：过磅-处罚-结案-查询案件文书链")
    void fullChainFlow() throws Exception {
        mockMvc.perform(post("/api/enforcement/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WEIGH_BODY.formatted("W-WEB-4")))
                .andExpect(status().isOk());

        String decisionBody = """
                {"requestId":"D-WEB-4","plateNo":"沪A10001","stationCode":"ST001",
                 "weighTime":"2026-09-01T02:00:00Z"}
                """;
        mockMvc.perform(post("/api/enforcement/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docType").value("PENALTY"))
                .andExpect(jsonPath("$.fineAmount").value(2000.00))
                .andExpect(jsonPath("$.points").value(3));

        String closeBody = """
                {"requestId":"C-WEB-4","plateNo":"沪A10001","stationCode":"ST001",
                 "weighTime":"2026-09-01T02:00:00Z"}
                """;
        String caseNo = mockMvc.perform(post("/api/enforcement/cases/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(closeBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"caseNo\":\"", "").replaceAll("\".*", "");

        mockMvc.perform(get("/api/enforcement/cases/" + caseNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.decisions[0].docType").value("PENALTY"));
    }

    @Test
    @DisplayName("非现场接入主流程：证据齐全自动生成 PENALTY，同键异参 409")
    void offsiteIngestMainFlowAndConflict() throws Exception {
        String body = """
                {"requestId":"%s","plateNo":"沪A10001","laneNo":"L01",
                 "weighTime":"2026-09-01T02:00:00Z","totalKg":40300.00,
                 "imageRef":"IMG-SYN-0001","deviceId":"DEV-L01"}
                """;
        mockMvc.perform(post("/api/enforcement/offsite/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("OS-WEB-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTO_DECIDED"))
                .andExpect(jsonPath("$.decision.docType").value("PENALTY"))
                .andExpect(jsonPath("$.decision.fineAmount").value(2000.00))
                .andExpect(jsonPath("$.decision.points").value(3))
                .andExpect(jsonPath("$.caseNo").isString());

        // 同键同参重放返回首次结果
        mockMvc.perform(post("/api/enforcement/offsite/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("OS-WEB-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 同键异参返回 409
        mockMvc.perform(post("/api/enforcement/offsite/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("OS-WEB-1").replace("40300.00", "50000.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_CONFLICT"));
    }

    @Test
    @DisplayName("非现场接入缺合成影像标识：转人工待补，不生成处罚决定")
    void offsiteMissingImageGoesManualPending() throws Exception {
        String body = """
                {"requestId":"OS-WEB-2","plateNo":"沪A10001","laneNo":"L01",
                 "weighTime":"2026-09-01T02:00:00Z","totalKg":40300.00,"deviceId":"DEV-L01"}
                """;
        mockMvc.perform(post("/api/enforcement/offsite/weighs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MANUAL_PENDING"))
                .andExpect(jsonPath("$.pendingReason").value("MISSING_IMAGE"))
                .andExpect(jsonPath("$.decision").doesNotExist());
    }
}
