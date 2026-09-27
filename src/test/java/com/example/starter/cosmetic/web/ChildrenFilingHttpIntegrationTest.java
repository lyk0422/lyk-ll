package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
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
 * 儿童备案真实 HTTP 入口（MockMvc + 完整 Spring 上下文 + H2）验证：
 * 提交/补正/变更/证据查询主流程，及 400/404/409/422 错误语义。
 */
class ChildrenFilingHttpIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setupMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CharacterEncodingFilter("UTF-8", true))
                .build();
    }

    private static String crossBandSubmitJson(String requestId, String productCode) {
        return """
                {
                  "requestId": "%s",
                  "productCode": "%s",
                  "productName": "合成儿童润肤霜",
                  "ageLowerMonths": 0,
                  "ageUpperMonths": 144,
                  "formulation": [
                    {"code": "WATER", "name": "水", "concentrationPct": 80.0000},
                    {"code": "GLYCERIN", "name": "甘油", "concentrationPct": 3.0000}
                  ],
                  "claims": ["MOISTURIZING"],
                  "safetyReports": [
                    {"reportNo": "SR-I-%s", "bandCode": "INFANT",
                     "coverageLowerMonths": 0, "coverageUpperMonths": 36,
                     "issuedDate": "2026-01-15", "conclusionSupported": true,
                     "conclusionText": "婴幼儿安全评估通过"},
                    {"reportNo": "SR-C-%s", "bandCode": "CHILD",
                     "coverageLowerMonths": 37, "coverageUpperMonths": 144,
                     "issuedDate": "2026-01-15", "conclusionSupported": true,
                     "conclusionText": "儿童安全评估通过"}
                  ],
                  "labels": [
                    {"bandCode": "INFANT", "labelText": "适用于0~36月婴幼儿",
                     "applicableLowerMonths": 0, "applicableUpperMonths": 36},
                    {"bandCode": "CHILD", "labelText": "适用于37~144月儿童",
                     "applicableLowerMonths": 37, "applicableUpperMonths": 144}
                  ],
                  "adultReports": []
                }
                """.formatted(requestId, productCode, requestId, requestId);
    }

    @Test
    void fullHttpLifecycle_submitCorrectExpireEvidence() throws Exception {
        // 1. 提交：200，进入公示，受理冻结报告清单/规则版本/标识摘要
        mockMvc.perform(post("/api/children-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossBandSubmitJson("REQ-HC-1", "P-HC-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"))
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-C-000001"))
                .andExpect(jsonPath("$.filing.versionNo").value(1))
                .andExpect(jsonPath("$.filing.ruleVersion").value(1))
                .andExpect(jsonPath("$.filing.labelDigest").isNotEmpty())
                .andExpect(jsonPath("$.filing.safetyReports.length()").value(2))
                .andExpect(jsonPath("$.filing.publicExpiresAtUtc").value("2026-07-10T00:00:00Z"));

        // 2. 材料补正：200，新修订 v2/r2，旧修订待审批准失效
        String correctionJson = """
                {
                  "requestId": "REQ-HC-1C",
                  "expectedVersion": 1,
                  "safetyReports": [
                    {"reportNo": "SR-I-1B", "bandCode": "INFANT",
                     "coverageLowerMonths": 0, "coverageUpperMonths": 36,
                     "issuedDate": "2026-02-01", "conclusionSupported": true,
                     "conclusionText": "补正后婴幼儿安全评估通过"},
                    {"reportNo": "SR-C-1B", "bandCode": "CHILD",
                     "coverageLowerMonths": 37, "coverageUpperMonths": 144,
                     "issuedDate": "2026-02-01", "conclusionSupported": true,
                     "conclusionText": "补正后儿童安全评估通过"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/children-filings/SYN-C-000001/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.versionNo").value(2))
                .andExpect(jsonPath("$.filing.revisionNo").value(2))
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"));

        // 3. 公示期满生效（仅最新修订）
        mockMvc.perform(post("/api/children-filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effected").value(1));

        mockMvc.perform(get("/api/children-filings/SYN-C-000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.versionNo").value(2));

        // 4. 判定证据查询：两个修订的冻结证据与审查明细
        mockMvc.perform(get("/api/children-filings/SYN-C-000001/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filingNo").value("SYN-C-000001"))
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].status").value("REVISION_SUPERSEDED"))
                .andExpect(jsonPath("$.revisions[1].status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.revisions[1].ruleVersion").value(1))
                .andExpect(jsonPath("$.revisions[1].labelDigest").isNotEmpty())
                .andExpect(jsonPath("$.revisions[1].safetyReports.length()").value(2))
                .andExpect(jsonPath("$.revisions[1].reviewDetails").isNotEmpty());
    }

    @Test
    void missingMaterialsReturns422WithAllProblems() throws Exception {
        String incomplete = """
                {
                  "requestId": "REQ-HC-2",
                  "productCode": "P-HC-2",
                  "productName": "合成儿童霜",
                  "ageLowerMonths": 0,
                  "ageUpperMonths": 144,
                  "formulation": [{"code": "WATER", "name": "水", "concentrationPct": 80.0000}],
                  "claims": ["MOISTURIZING"],
                  "safetyReports": [],
                  "labels": [],
                  "adultReports": []
                }
                """;
        mockMvc.perform(post("/api/children-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incomplete))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHILD_REVIEW_REJECTED"))
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems[*]", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("BAND_SAFETY_REPORT"))))
                .andExpect(jsonPath("$.problems[*]", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("BAND_AGE_LABEL"))));
    }

    @Test
    void invalidAgeRangeReturns400() throws Exception {
        String invalid = """
                {
                  "requestId": "REQ-HC-3",
                  "productCode": "P-HC-3",
                  "productName": "合成儿童霜",
                  "ageLowerMonths": 0,
                  "ageUpperMonths": 200,
                  "formulation": [{"code": "WATER", "name": "水", "concentrationPct": 80.0000}],
                  "claims": ["MOISTURIZING"],
                  "safetyReports": [],
                  "labels": [],
                  "adultReports": []
                }
                """;
        mockMvc.perform(post("/api/children-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingFilingReturns404() throws Exception {
        mockMvc.perform(get("/api/children-filings/SYN-C-999999"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/children-filings/SYN-C-999999/evidence"))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionMismatchReturns409() throws Exception {
        mockMvc.perform(post("/api/children-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossBandSubmitJson("REQ-HC-4", "P-HC-4")))
                .andExpect(status().isOk());

        String staleVersion = """
                {
                  "requestId": "REQ-HC-4C",
                  "expectedVersion": 5,
                  "safetyReports": []
                }
                """;
        mockMvc.perform(post("/api/children-filings/SYN-C-000001/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleVersion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void idempotencyParamMismatchReturns409AndReplayReturns200() throws Exception {
        mockMvc.perform(post("/api/children-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossBandSubmitJson("REQ-HC-5", "P-HC-5")))
                .andExpect(status().isOk());

        // 同键同参：重放首次冻结结果
        mockMvc.perform(post("/api/children-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossBandSubmitJson("REQ-HC-5", "P-HC-5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-C-000001"));

        // 同键异参：409
        String different = crossBandSubmitJson("REQ-HC-5", "P-HC-5")
                .replace("合成儿童润肤霜", "改名儿童霜");
        mockMvc.perform(post("/api/children-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));
    }

    @Test
    void substantiveChangeViaHttpCreatesNewFiling() throws Exception {
        mockMvc.perform(post("/api/children-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossBandSubmitJson("REQ-HC-6", "P-HC-6")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/children-filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk());

        // 年龄范围改变：实质变更，新备案编号，重新公示
        String changeJson = """
                {
                  "requestId": "REQ-HC-6X",
                  "expectedVersion": 1,
                  "ageLowerMonths": 37,
                  "ageUpperMonths": 144,
                  "safetyReports": [
                    {"reportNo": "SR-C-6X", "bandCode": "CHILD",
                     "coverageLowerMonths": 37, "coverageUpperMonths": 144,
                     "issuedDate": "2026-02-01", "conclusionSupported": true,
                     "conclusionText": "儿童安全评估通过"}
                  ],
                  "labels": [
                    {"bandCode": "CHILD", "labelText": "适用于37~144月儿童",
                     "applicableLowerMonths": 37, "applicableUpperMonths": 144}
                  ]
                }
                """;
        mockMvc.perform(post("/api/children-filings/products/P-HC-6/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.changeType").value("SUBSTANTIVE"))
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-C-000002"))
                .andExpect(jsonPath("$.filing.versionNo").value(2))
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"));
    }
}
