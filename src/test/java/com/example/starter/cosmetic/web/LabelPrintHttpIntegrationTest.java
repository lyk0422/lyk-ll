package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实 HTTP 入口（MockMvc + 完整 Spring 上下文 + H2）验证印次台账主链路：
 * 备案生效 → 查询期望摘要 → 登记 100 张印次 → 领 60 张 → 换版 → 旧剩余拒领，
 * 以及查询端张数守恒与禁用剩余拆分。
 */
class LabelPrintHttpIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setupMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CharacterEncodingFilter("UTF-8", true))
                .build();
    }

    @Test
    void printRunLifecycle_draw60Of100ThenChangeRejectsOldRemaining() throws Exception {
        // 1. 合成成人备案提交并公示期满生效
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-F1",
                                  "productCode": "P-HP-1",
                                  "productName": "合成保湿乳",
                                  "formulation": [
                                    {"code": "WATER", "name": "水", "concentrationPct": 78.0000},
                                    {"code": "GLYCERIN", "name": "甘油", "concentrationPct": 5.0000}
                                  ],
                                  "claims": ["MOISTURIZING"],
                                  "reports": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"));
        mockMvc.perform(post("/api/filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effected").value(1));

        // 2. 查询当前有效备案的标签摘要期望
        MvcResult digestResult = mockMvc.perform(get("/api/label-print-runs/expected-digest")
                        .param("productCode", "P-HP-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filingNo").value("SYN-F-000001"))
                .andExpect(jsonPath("$.filingVersionNo").value(1))
                .andReturn();
        JsonNode digest = objectMapper.readTree(digestResult.getResponse().getContentAsString());
        String labelDigest = digest.get("labelDigest").asText();

        // 3. 登记 100 张印次
        mockMvc.perform(post("/api/label-print-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-PR1",
                                  "printRunNo": "PR-HTTP-1",
                                  "productCode": "P-HP-1",
                                  "printedCount": 100,
                                  "labelDigest": "%s"
                                }
                                """.formatted(labelDigest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printRun.status").value("ACTIVE"))
                .andExpect(jsonPath("$.printRun.version").value(1))
                .andExpect(jsonPath("$.printRun.remainingCount").value(100));

        // 4. 领用 60 张（包装批 BATCH-HTTP-1 首次登记属于 P-HP-1）
        mockMvc.perform(post("/api/label-print-runs/PR-HTTP-1/draws")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-D1",
                                  "batchNo": "BATCH-HTTP-1",
                                  "quantity": 60,
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printRunVersion").value(2))
                .andExpect(jsonPath("$.remainingAfter").value(40));

        // 5. 包装文字简易变更：新修订即时生效，旧印次剩余标签原子禁用
        mockMvc.perform(post("/api/filings/products/P-HP-1/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-C1",
                                  "packagingText": "合成新包装文字V2"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.filing.versionNo").value(2));

        // 6. 旧印次剩余 40 张拒领：422 PRINT_RUN_DISABLED
        mockMvc.perform(post("/api/label-print-runs/PR-HTTP-1/draws")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-D2",
                                  "batchNo": "BATCH-HTTP-1",
                                  "quantity": 40,
                                  "expectedVersion": 3
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRINT_RUN_DISABLED"));

        // 7. 查询核对：100 = 60 已领 + 0 已毁 + 40 禁用剩余
        mockMvc.perform(get("/api/label-print-runs/PR-HTTP-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.printedCount").value(100))
                .andExpect(jsonPath("$.drawnCount").value(60))
                .andExpect(jsonPath("$.destroyedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(40))
                .andExpect(jsonPath("$.disabledRemainingCount").value(40));

        // 8. 已领凭据可追溯且已追加风险关联
        mockMvc.perform(get("/api/label-print-runs/PR-HTTP-1/draws"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchNo").value("BATCH-HTTP-1"))
                .andExpect(jsonPath("$[0].quantity").value(60))
                .andExpect(jsonPath("$[0].riskFlag").value(true));
    }

    @Test
    void httpErrorSemantics_missingRun404_staleVersion409_overBalance422() throws Exception {
        // 印次不存在：404
        mockMvc.perform(get("/api/label-print-runs/PR-NONE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 准备有效备案与 100 张印次
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-F2",
                                  "productCode": "P-HP-2",
                                  "productName": "合成保湿乳",
                                  "formulation": [
                                    {"code": "WATER", "name": "水", "concentrationPct": 78.0000},
                                    {"code": "GLYCERIN", "name": "甘油", "concentrationPct": 5.0000}
                                  ],
                                  "claims": ["MOISTURIZING"],
                                  "reports": []
                                }
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk());
        MvcResult digestResult = mockMvc.perform(get("/api/label-print-runs/expected-digest")
                        .param("productCode", "P-HP-2"))
                .andExpect(status().isOk())
                .andReturn();
        String labelDigest = objectMapper.readTree(digestResult.getResponse().getContentAsString())
                .get("labelDigest").asText();
        mockMvc.perform(post("/api/label-print-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-PR2",
                                  "printRunNo": "PR-HTTP-2",
                                  "productCode": "P-HP-2",
                                  "printedCount": 100,
                                  "labelDigest": "%s"
                                }
                                """.formatted(labelDigest)))
                .andExpect(status().isOk());

        // 旧版 expectedVersion：409
        mockMvc.perform(post("/api/label-print-runs/PR-HTTP-2/draws")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-D3",
                                  "batchNo": "BATCH-HTTP-2",
                                  "quantity": 10,
                                  "expectedVersion": 9
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 超未领余额：422
        mockMvc.perform(post("/api/label-print-runs/PR-HTTP-2/draws")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-D4",
                                  "batchNo": "BATCH-HTTP-2",
                                  "quantity": 101,
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        // 非正整数张数：400
        mockMvc.perform(post("/api/label-print-runs/PR-HTTP-2/draws")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HP-D5",
                                  "batchNo": "BATCH-HTTP-2",
                                  "quantity": 0,
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
