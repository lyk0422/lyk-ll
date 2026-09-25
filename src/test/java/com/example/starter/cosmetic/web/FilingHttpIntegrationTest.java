package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.testsupport.FilingFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实 HTTP 入口（MockMvc + 完整 Spring 上下文 + H2）验证：
 * 备案提交→公示、同键异参 409、期满生效、简易变更、公示异议、注销终态全链路。
 */
class FilingHttpIntegrationTest extends AbstractCosmeticIntegrationTest {

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

    private String submitJson(String requestId, String product, String name, List<String> claims) {
        return """
                {
                  "requestId": "%s",
                  "productCode": "%s",
                  "productName": "%s",
                  "formulation": [
                    {"code": "WATER", "name": "水", "concentrationPct": 78.0000},
                    {"code": "GLYCERIN", "name": "甘油", "concentrationPct": 5.0000}
                  ],
                  "claims": %s,
                  "reports": []
                }
                """.formatted(requestId, product, name, claimsJson(claims));
    }

    private String claimsJson(List<String> claims) {
        return objectMapper.valueToTree(claims).toString();
    }

    @Test
    void fullHttpLifecycle_submitConflictExpiryChangeObjectionWithdraw() throws Exception {
        // 1. 首次备案提交：200，进入公示
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-HTTP-1", "P-HTTP-1", "合成保湿乳", List.of("MOISTURIZING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"))
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-F-000001"))
                .andExpect(jsonPath("$.filing.publicExpiresAtUtc").value("2026-07-10T00:00:00Z"));

        // 2. 同键异参：409
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-HTTP-1", "P-HTTP-1", "改名产品", List.of("MOISTURIZING"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));

        // 3. 同键同参重放：200，idempotentReplay=true
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-HTTP-1", "P-HTTP-1", "合成保湿乳", List.of("MOISTURIZING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 4. 恰第10日24:00 UTC 期满生效
        mockMvc.perform(post("/api/filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effected").value(1));

        mockMvc.perform(get("/api/filings/SYN-F-000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));

        // 5. 简易变更（包装文字）：引用原编号、即时生效、v2
        String changeJson = """
                {
                  "requestId": "REQ-HTTP-2",
                  "productName": null,
                  "formulation": null,
                  "claims": null,
                  "reports": null,
                  "packagingText": "新包装：避免阳光直射"
                }
                """;
        mockMvc.perform(post("/api/filings/products/P-HTTP-1/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.changeType").value("SIMPLE"))
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-F-000001"))
                .andExpect(jsonPath("$.filing.versionNo").value(2))
                .andExpect(jsonPath("$.filing.status").value("EFFECTIVE"));

        // 历史链：两版本
        mockMvc.perform(get("/api/filings/products/P-HTTP-1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 审查明细可复算
        mockMvc.perform(get("/api/filings/SYN-F-000001/reviews").param("versionNo", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)));

        // 6. 注销终态
        mockMvc.perform(post("/api/filings/SYN-F-000001/withdrawal")
                        .param("requestId", "REQ-HTTP-3")
                        .param("reason", "企业主动注销"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("WITHDRAWN"));

        // 7. 重复注销幂等成功
        mockMvc.perform(post("/api/filings/SYN-F-000001/withdrawal")
                        .param("requestId", "REQ-HTTP-3")
                        .param("reason", "企业主动注销"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("WITHDRAWN"));

        // 8. 注销后新宣称审查拒绝：形式驳回
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-HTTP-4", "P-HTTP-1", "合成保湿乳", List.of("MOISTURIZING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("FORM_REJECTED"))
                .andExpect(jsonPath("$.filing.rejectReason").value(
                        org.hamcrest.Matchers.containsString("注销后新宣称审查一律拒绝")));
    }

    @Test
    void publicityObjectionRaceViaHttp_objectionWinsBeforeExpiry() throws Exception {
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-HTTP-O-1", "P-HTTP-O", "合成乳", List.of("MOISTURIZING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"));

        // 公示期内异议受理成功
        mockMvc.perform(post("/api/filings/SYN-F-000001/objections")
                        .param("reason", "消费者异议")
                        .param("atUtc", "2026-07-05T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("OBJECTION_REVIEW"));

        // 异议成立：终态驳回
        mockMvc.perform(post("/api/filings/SYN-F-000001/objections/resolution")
                        .param("upheld", "true")
                        .param("conclusion", "宣称依据不足"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("REJECTED_AFTER_OBJECTION"));

        // 期满扫描不得使其生效
        mockMvc.perform(post("/api/filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effected").value(0));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        // 空配方触发 @Valid 校验
        String invalid = """
                {
                  "requestId": "REQ-BAD",
                  "productCode": "P-BAD",
                  "productName": "x",
                  "formulation": [],
                  "claims": ["MOISTURIZING"],
                  "reports": []
                }
                """;
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void notFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/filings/NO-SUCH-NO"))
                .andExpect(status().isNotFound());
    }

    @Test
    void substantiveChangeWithThreeYearExactReportPassesViaHttp() throws Exception {
        // 首次生效
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-HTTP-S1", "P-HTTP-S", "乳", List.of("MOISTURIZING"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk());

        // 实质变更加防晒，报告恰满3年（2023-06-30 相对当前提交日 2026-06-30），浓度恰等
        String change = """
                {
                  "requestId": "REQ-HTTP-S2",
                  "productName": null,
                  "formulation": [
                    {"code": "WATER", "name": "水", "concentrationPct": 50.0000},
                    {"code": "TITANIUM_DIOXIDE", "name": "二氧化钛", "concentrationPct": 25.0000}
                  ],
                  "claims": ["MOISTURIZING", "SUNSCREEN"],
                  "reports": [
                    {"reportNo": "R-HTTP-S", "efficacyCode": "SUNSCREEN",
                     "issuedDate": "2023-06-30", "conclusionSupported": true,
                     "conclusionText": "支持防晒"}
                  ],
                  "packagingText": null
                }
                """;
        mockMvc.perform(post("/api/filings/products/P-HTTP-S/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.changeType").value("SUBSTANTIVE"))
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-F-000002"))
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"))
                .andExpect(jsonPath("$.filing.parentFilingNo").value("SYN-F-000001"));
    }
}
