package com.example.starter.cosmetic.child.web;

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
 * 儿童分龄备案真实 HTTP 入口（MockMvc + 完整 Spring 上下文 + H2）验证：
 * 提交受理冻结→公示期满生效→简易/实质变更、补正、判定证据查询，
 * 以及 400/404/409/422 错误语义。
 */
class ChildFilingHttpIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setupMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CharacterEncodingFilter("UTF-8", true))
                .build();
    }

    private String lowBandSubmitJson(String requestId, String product) {
        return """
                {
                  "requestId": "%s",
                  "productCode": "%s",
                  "productName": "合成婴儿润肤乳",
                  "ageLowerMonths": 0,
                  "ageUpperMonths": 36,
                  "claims": ["CH_MOISTURIZE"],
                  "formulation": [
                    {"code": "WATER", "name": "水", "concentrationPct": 90.0000},
                    {"code": "GLYCERIN", "name": "甘油", "concentrationPct": 3.0000}
                  ],
                  "safetyReports": [
                    {"reportNo": "SR-%s", "bandCode": "BAND_0_36",
                     "coverLowerMonths": 0, "coverUpperMonths": 36,
                     "conclusionText": "低龄档安全评估结论：安全"}
                  ],
                  "labels": [
                    {"bandCode": "BAND_0_36", "labelText": "适用于0~36个月婴幼儿"}
                  ],
                  "packagingText": "包装：置于婴幼儿不可触及处"
                }
                """.formatted(requestId, product, requestId);
    }

    @Test
    void fullHttpLifecycle_submitExpiryChangeEvidence() throws Exception {
        // 1. 提交：200，受理冻结并进入公示
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lowBandSubmitJson("REQ-CH-1", "PC-HTTP-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-C-000001"))
                .andExpect(jsonPath("$.filing.status").value("IN_PUBLICITY"))
                .andExpect(jsonPath("$.filing.versionNo").value(1))
                .andExpect(jsonPath("$.filing.currentRevision.ruleVersion").value("CHILD-AGE-2026A"))
                .andExpect(jsonPath("$.filing.currentRevision.labelDigest").isNotEmpty())
                .andExpect(jsonPath("$.filing.currentRevision.publicExpiresAtUtc")
                        .value("2026-07-10T00:00:00Z"));

        // 2. 分龄规则合成初始化可查
        mockMvc.perform(get("/api/child-filings/age-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value("CHILD-AGE-2026A"))
                .andExpect(jsonPath("$.bands.length()").value(2));

        // 3. 公示期满生效
        mockMvc.perform(post("/api/child-filings/publicity-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effected").value(1));
        mockMvc.perform(get("/api/child-filings/SYN-C-000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));

        // 4. 简易变更（包装文字）：即时生效，版本 +1
        mockMvc.perform(post("/api/child-filings/SYN-C-000001/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-CH-2", "expectedVersion": 1, "changeKind": "SIMPLE",
                                 "packagingText": "新包装：避免阳光直射"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.versionNo").value(2))
                .andExpect(jsonPath("$.filing.currentRevision.revisionType").value("SIMPLE"))
                .andExpect(jsonPath("$.filing.currentRevision.status").value("EFFECTIVE"));

        // 5. 判定证据查询
        mockMvc.perform(get("/api/child-filings/SYN-C-000001/evidence").param("revisionNo", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$[0].stage").value("CHILD_REVIEW"));

        // 6. 修订链查询
        mockMvc.perform(get("/api/child-filings/SYN-C-000001/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void httpErrorSemantics_400_404_409_422() throws Exception {
        // 400：年龄下限大于上限（参数非法）
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-CH-E1", "productCode": "PC-HTTP-E1",
                                 "productName": "x", "ageLowerMonths": 37, "ageUpperMonths": 36,
                                 "claims": ["CH_MOISTURIZE"],
                                 "formulation": [{"code": "WATER", "name": "水", "concentrationPct": 90}],
                                 "safetyReports": [], "labels": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        // 400：缺少必填字段（Bean 校验）
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-CH-E2", "productCode": "PC-HTTP-E2",
                                 "productName": "x", "ageLowerMonths": 0,
                                 "claims": ["CH_MOISTURIZE"],
                                 "formulation": [{"code": "WATER", "name": "水", "concentrationPct": 90}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 404：备案不存在
        mockMvc.perform(get("/api/child-filings/SYN-C-999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 409：同键异参
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lowBandSubmitJson("REQ-CH-E3", "PC-HTTP-E3")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lowBandSubmitJson("REQ-CH-E3", "PC-HTTP-E3")
                                .replace("合成婴儿润肤乳", "改名产品")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));

        // 先生效，再验证 422
        mockMvc.perform(post("/api/child-filings/publicity-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk());

        // 422：expectedVersion 不一致
        mockMvc.perform(post("/api/child-filings/SYN-C-000001/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-CH-E4", "expectedVersion": 99, "changeKind": "SIMPLE",
                                 "packagingText": "新包装"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));

        // 422：伪装简易变更（声明 SIMPLE 实际改了年龄范围）
        mockMvc.perform(post("/api/child-filings/SYN-C-000001/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-CH-E5", "expectedVersion": 1, "changeKind": "SIMPLE",
                                 "ageUpperMonths": 144}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHILD_CHANGE_MUST_BE_SUBSTANTIVE"));
    }

    @Test
    void correctionViaHttpGeneratesNewRevision() throws Exception {
        // 跨档缺材料提交：零生效，缺项一次列全
        mockMvc.perform(post("/api/child-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-CH-C1", "productCode": "PC-HTTP-C1",
                                 "productName": "合成儿童乳", "ageLowerMonths": 0, "ageUpperMonths": 144,
                                 "claims": ["CH_MOISTURIZE"],
                                 "formulation": [{"code": "WATER", "name": "水", "concentrationPct": 90}],
                                 "safetyReports": [
                                   {"reportNo": "SR-C1", "bandCode": "BAND_0_36",
                                    "coverLowerMonths": 0, "coverUpperMonths": 36}
                                 ],
                                 "labels": [
                                   {"bandCode": "BAND_0_36", "labelText": "适用于0~36个月婴幼儿"}
                                 ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("REVIEW_REJECTED"))
                .andExpect(jsonPath("$.filing.currentRevision.rejectReason")
                        .value(org.hamcrest.Matchers.containsString("BAND_37_144")));

        // 补正：两档材料齐备，生成新修订并公示
        mockMvc.perform(post("/api/child-filings/SYN-C-000001/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-CH-C2", "expectedVersion": 1,
                                 "ageLowerMonths": 0, "ageUpperMonths": 144,
                                 "claims": ["CH_MOISTURIZE"],
                                 "formulation": [{"code": "WATER", "name": "水", "concentrationPct": 90}],
                                 "safetyReports": [
                                   {"reportNo": "SR-C2-L", "bandCode": "BAND_0_36",
                                    "coverLowerMonths": 0, "coverUpperMonths": 36},
                                   {"reportNo": "SR-C2-H", "bandCode": "BAND_37_144",
                                    "coverLowerMonths": 37, "coverUpperMonths": 144}
                                 ],
                                 "labels": [
                                   {"bandCode": "BAND_0_36", "labelText": "适用于0~36个月婴幼儿"},
                                   {"bandCode": "BAND_37_144", "labelText": "适用于3~12岁儿童"}
                                 ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.versionNo").value(2))
                .andExpect(jsonPath("$.filing.status").value("IN_PUBLICITY"))
                .andExpect(jsonPath("$.filing.currentRevision.revisionType").value("CORRECTION"))
                .andExpect(jsonPath("$.filing.currentRevision.status").value("PUBLIC"));
    }
}
