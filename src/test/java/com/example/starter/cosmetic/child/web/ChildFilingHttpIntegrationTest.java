package com.example.starter.cosmetic.child.web;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 儿童分龄备案真实 HTTP 入口测试（MockMvc + 完整 Spring 上下文 + H2）：
 * 规则查询、跨档提交/驳回、补正、公示生效、实质变更、幂等 409 与 400/404/422。
 */
class ChildFilingHttpIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    private static final String AT = "2026-06-30T08:00:00Z";

    @BeforeEach
    void setupMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CharacterEncodingFilter("UTF-8", true))
                .build();
    }

    /** 合格跨档 [0,144] 提交 JSON（两档独立报告，覆盖各自年龄段）。 */
    private String crossTierSubmitJson(String requestId, String product, String label, boolean includeOldReport) {
        String oldReport = includeOldReport ? """
                ,{"reportNo":"CR-O-%s","tierCode":"TIER_37_144","issuedDate":"2026-01-01",
                  "coversFromMonths":37,"coversToMonths":144,"conclusionSupported":true,
                  "conclusionText":"支持37-144月"}
                """.formatted(requestId) : "";
        return """
                {
                  "requestId": "%s",
                  "atUtc": "%s",
                  "productCode": "%s",
                  "productName": "合成儿童润肤霜",
                  "ageFromMonths": 0,
                  "ageToMonths": 144,
                  "formulation": [
                    {"code":"WATER","name":"水","concentrationPct":78.0000},
                    {"code":"GLYCERIN","name":"甘油","concentrationPct":5.0000}
                  ],
                  "claims": ["CHILD_MOISTURIZING"],
                  "safetyReports": [
                    {"reportNo":"CR-Y-%s","tierCode":"TIER_0_36","issuedDate":"2026-01-01",
                     "coversFromMonths":0,"coversToMonths":36,"conclusionSupported":true,
                     "conclusionText":"支持0-36月"}%s
                  ],
                  "ageLabelText": "%s"
                }
                """.formatted(requestId, AT, product, requestId, oldReport, label);
    }

    private String correctionJson(String requestId, int expectedVersion) {
        return """
                {
                  "requestId": "%s",
                  "atUtc": "%s",
                  "expectedVersion": %d,
                  "productName": "合成儿童润肤霜",
                  "ageFromMonths": 0,
                  "ageToMonths": 144,
                  "formulation": [
                    {"code":"WATER","name":"水","concentrationPct":78.0000},
                    {"code":"GLYCERIN","name":"甘油","concentrationPct":5.0000}
                  ],
                  "claims": ["CHILD_MOISTURIZING"],
                  "safetyReports": [
                    {"reportNo":"CR-YC-%s","tierCode":"TIER_0_36","issuedDate":"2026-01-01",
                     "coversFromMonths":0,"coversToMonths":36,"conclusionSupported":true,"conclusionText":"y"},
                    {"reportNo":"CR-OC-%s","tierCode":"TIER_37_144","issuedDate":"2026-01-01",
                     "coversFromMonths":37,"coversToMonths":144,"conclusionSupported":true,"conclusionText":"o"}
                  ],
                  "ageLabelText": "适用年龄 0-144 月（出生至144月龄）"
                }
                """.formatted(requestId, AT, expectedVersion, requestId, requestId);
    }

    @Test
    void ageRuleInitIsServed() throws Exception {
        mockMvc.perform(get("/api/child-filings/age-rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value("CHILD-AGE-RULE-V1"))
                .andExpect(jsonPath("$.tiers.length()").value(2))
                .andExpect(jsonPath("$.limits.TIER_0_36.SALICYLIC_ACID").value(0.5000));
    }

    @Test
    void fullChildLifecycle_submitRejectCorrectExpiryChange() throws Exception {
        // 1. 跨档缺大龄档报告 → 形式驳回（一次列全），零生效
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossTierSubmitJson("REQ-H-1", "CP-H", "适用年龄 0-144 月", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-CF-000001"))
                .andExpect(jsonPath("$.filing.versionNo").value(1))
                .andExpect(jsonPath("$.filing.status").value("FORM_REJECTED"))
                .andExpect(jsonPath("$.filing.rejectReason").value(
                        org.hamcrest.Matchers.containsString("TIER_37_144")));

        // 2. 补正：补齐两档材料，同号 v2 进公示
        mockMvc.perform(post("/api/child-filings/SYN-CF-000001/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionJson("REQ-H-2", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.versionNo").value(2))
                .andExpect(jsonPath("$.filing.changeType").value("CORRECTION"))
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"))
                .andExpect(jsonPath("$.filing.requiredTiers[1]").value("TIER_37_144"));

        // 3. 同键同参重放 → idempotentReplay=true，不新增修订
        mockMvc.perform(post("/api/child-filings/SYN-CF-000001/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionJson("REQ-H-2", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 4. 旧 v1 已 VOID；期满仅 v2 生效
        mockMvc.perform(get("/api/child-filings/products/CP-H/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(post("/api/child-filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effected").value(1));
        mockMvc.perform(get("/api/child-filings/SYN-CF-000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.versionNo").value(2));

        // 5. 生效后再补正 → 422
        mockMvc.perform(post("/api/child-filings/SYN-CF-000001/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionJson("REQ-H-3", 2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHILD_CORRECTION_NOT_ALLOWED"));
    }

    @Test
    void crossTierCompleteSubmissionPassesAndIdempotencyMismatchConflicts() throws Exception {
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossTierSubmitJson("REQ-I-1", "CP-I", "适用年龄 0-144 月", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"))
                .andExpect(jsonPath("$.filing.ageRuleVersion").value("CHILD-AGE-RULE-V1"))
                .andExpect(jsonPath("$.filing.ageLabelDigest").isNotEmpty());

        // 同键同参重放
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossTierSubmitJson("REQ-I-1", "CP-I", "适用年龄 0-144 月", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 同键不同精确时刻 → 异参 409
        String otherTime = crossTierSubmitJson("REQ-I-1", "CP-I", "适用年龄 0-144 月", true)
                .replace(AT, "2026-06-30T09:00:00Z");
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherTime))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHILD_IDEMPOTENCY_PARAM_MISMATCH"));
    }

    @Test
    void invalidAgeRangeReturns400AndMissingAtUtcReturns400() throws Exception {
        // 下限 > 上限
        String inverted = crossTierSubmitJson("REQ-J-1", "CP-J", "适用年龄", true)
                .replace("\"ageFromMonths\": 0", "\"ageFromMonths\": 100")
                .replace("\"ageToMonths\": 144", "\"ageToMonths\": 50");
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inverted))
                .andExpect(status().isBadRequest());

        // 缺少 atUtc → Bean Validation 400
        String noAt = crossTierSubmitJson("REQ-J-2", "CP-J2", "适用年龄 0-144 月", true)
                .replace("\"atUtc\": \"" + AT + "\",", "");
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noAt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownChildFilingReturns404() throws Exception {
        mockMvc.perform(get("/api/child-filings/SYN-CF-999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ageChangeOnEffectiveFilingIsSubstantiveAndExpectedVersionChecked() throws Exception {
        // 提交合格低龄件并生效
        String young = """
                {
                  "requestId":"REQ-K-1","atUtc":"%s","productCode":"CP-K","productName":"儿童霜",
                  "ageFromMonths":0,"ageToMonths":36,
                  "formulation":[{"code":"WATER","name":"水","concentrationPct":80.0000}],
                  "claims":["CHILD_MOISTURIZING"],
                  "safetyReports":[{"reportNo":"CR-K-1","tierCode":"TIER_0_36","issuedDate":"2026-01-01",
                    "coversFromMonths":0,"coversToMonths":36,"conclusionSupported":true,"conclusionText":"ok"}],
                  "ageLabelText":"适用年龄 0-36 月"
                }
                """.formatted(AT);
        mockMvc.perform(post("/api/child-filings").contentType(MediaType.APPLICATION_JSON).content(young))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/child-filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk());

        // 过期 expectedVersion=99 → 409
        String staleChange = """
                {
                  "requestId":"REQ-K-2","atUtc":"%s","expectedVersion":99,
                  "packagingText":"仅改包装"
                }
                """.formatted(AT);
        mockMvc.perform(post("/api/child-filings/products/CP-K/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(staleChange))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHILD_VERSION_MISMATCH"));

        // 年龄区间改变 → 实质变更，新编号
        String ageChange = """
                {
                  "requestId":"REQ-K-3","atUtc":"%s","expectedVersion":1,
                  "ageFromMonths":0,"ageToMonths":144,
                  "formulation":[{"code":"WATER","name":"水","concentrationPct":80.0000}],
                  "claims":["CHILD_MOISTURIZING"],
                  "safetyReports":[
                    {"reportNo":"CR-K-Y","tierCode":"TIER_0_36","issuedDate":"2026-01-01",
                     "coversFromMonths":0,"coversToMonths":36,"conclusionSupported":true,"conclusionText":"y"},
                    {"reportNo":"CR-K-O","tierCode":"TIER_37_144","issuedDate":"2026-01-01",
                     "coversFromMonths":37,"coversToMonths":144,"conclusionSupported":true,"conclusionText":"o"}
                  ],
                  "ageLabelText":"适用年龄 0-144 月",
                  "packagingText":"包装也改了"
                }
                """.formatted(AT);
        mockMvc.perform(post("/api/child-filings/products/CP-K/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(ageChange))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.changeType").value("SUBSTANTIVE"))
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-CF-000002"))
                .andExpect(jsonPath("$.filing.parentFilingNo").value("SYN-CF-000001"))
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"));
    }
}
