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
 * 儿童备案 HTTP 入口测试（MockMvc + 完整 Spring 上下文 + H2）：
 * 提交/补正/证据/分龄规则查询，以及 400/404/409/422 错误语义。
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

    private String childSubmitJson(String requestId, String product, int lower, int upper,
                                   String dossiers) {
        return """
                {
                  "requestId": "%s",
                  "productCode": "%s",
                  "productName": "合成儿童保湿乳",
                  "formulation": [
                    {"code": "WATER", "name": "水", "concentrationPct": 78.0000},
                    {"code": "GLYCERIN", "name": "甘油", "concentrationPct": 5.0000}
                  ],
                  "claims": ["MOISTURIZING"],
                  "reports": [],
                  "filingCategory": "CHILD",
                  "ageLowerMonths": %d,
                  "ageUpperMonths": %d,
                  "childDossiers": %s
                }
                """.formatted(requestId, product, lower, upper, dossiers);
    }

    private static final String BAND036_DOSSIER = """
            [{"bandCode": "BAND_0_36", "reportNo": "CS-HTTP-1",
              "coverageLowerMonths": 0, "coverageUpperMonths": 36,
              "conclusionSupported": true, "conclusionText": "结论支持0-36月使用安全",
              "labelText": "适用于0-36个月婴幼儿"}]
            """;

    @Test
    void childSubmitCorrectionAndEvidenceViaHttp() throws Exception {
        // 1. 儿童提交缺材料 → 200 + FORM_REJECTED（缺项一次列全、零生效）
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(childSubmitJson("REQ-HC-1", "P-HC-1", 0, 36, "[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("FORM_REJECTED"))
                .andExpect(jsonPath("$.filing.filingCategory").value("CHILD"))
                .andExpect(jsonPath("$.filing.rejectReason")
                        .value(org.hamcrest.Matchers.containsString("CHILD_BAND_MATERIAL")));

        // 2. 补正（expectedVersion=1，补齐材料）→ 200 + v2 PUBLIC
        String correction = """
                {
                  "requestId": "REQ-HC-1B",
                  "expectedVersion": 1,
                  "childDossiers": %s
                }
                """.formatted(BAND036_DOSSIER);
        mockMvc.perform(post("/api/filings/SYN-F-000001/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.versionNo").value(2))
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"))
                .andExpect(jsonPath("$.filing.ageRuleVersion").value("AGE-RULE-V1"))
                .andExpect(jsonPath("$.filing.labelDigest").isNotEmpty());

        // 3. 判定证据查询：冻结报告清单、规则版本、标识摘要
        mockMvc.perform(get("/api/filings/SYN-F-000001/child-evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.ageRuleVersion").value("AGE-RULE-V1"))
                .andExpect(jsonPath("$.frozenDossiers[0].reportNo").value("CS-HTTP-1"))
                .andExpect(jsonPath("$.reviewDetails.length()")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)));

        // 4. 分龄规则合成初始化查询
        mockMvc.perform(get("/api/filings/age-rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value("AGE-RULE-V1"))
                .andExpect(jsonPath("$.bands[0].bandCode").value("BAND_0_36"))
                .andExpect(jsonPath("$.bands[1].upperMonths").value(144));

        // 5. 公示期满生效
        mockMvc.perform(post("/api/filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effected").value(1));
        mockMvc.perform(get("/api/filings/SYN-F-000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.versionNo").value(2));
    }

    @Test
    void invalidAgeRangeReturns400() throws Exception {
        // 下限大于上限
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(childSubmitJson("REQ-HC-2", "P-HC-2", 40, 36, BAND036_DOSSIER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        // 年龄越界（>144）由 Bean 校验拦截
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(childSubmitJson("REQ-HC-2B", "P-HC-2B", 0, 145, BAND036_DOSSIER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctionErrorsMapToProperStatuses() throws Exception {
        // 补正对象不存在 → 404
        mockMvc.perform(post("/api/filings/SYN-F-999999/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-HC-3", "expectedVersion": 1,
                                 "childDossiers": []}
                                """))
                .andExpect(status().isNotFound());

        // 成人备案补正 → 422
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "REQ-HC-3B",
                                  "productCode": "P-HC-3",
                                  "productName": "合成保湿乳",
                                  "formulation": [
                                    {"code": "WATER", "name": "水", "concentrationPct": 78.0000}
                                  ],
                                  "claims": ["MOISTURIZING"],
                                  "reports": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"));
        mockMvc.perform(post("/api/filings/SYN-F-000001/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-HC-3C", "expectedVersion": 1,
                                 "childDossiers": []}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_CHILD_FILING"));

        // 儿童待补正备案：expectedVersion 不一致 → 409
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(childSubmitJson("REQ-HC-3D", "P-HC-3D", 0, 36, "[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("FORM_REJECTED"));
        mockMvc.perform(post("/api/filings/SYN-F-000002/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-HC-3E", "expectedVersion": 9,
                                 "childDossiers": []}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 成人备案查询儿童证据 → 422
        mockMvc.perform(get("/api/filings/SYN-F-000001/child-evidence"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void childChangeWithAgeChangeIsSubstantiveViaHttp() throws Exception {
        // 儿童备案提交并生效
        mockMvc.perform(post("/api/filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(childSubmitJson("REQ-HC-4", "P-HC-4", 0, 36, BAND036_DOSSIER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.status").value("PUBLIC"));
        mockMvc.perform(post("/api/filings/public-notice-expiry")
                        .param("atUtc", "2026-07-10T00:00:00Z"))
                .andExpect(status().isOk());

        // 年龄范围变更（伪装包装文字变更）：仍须实质变更
        String change = """
                {
                  "requestId": "REQ-HC-4B",
                  "expectedVersion": 1,
                  "packagingText": "新包装文字",
                  "ageUpperMonths": 30,
                  "childDossiers": [
                    {"bandCode": "BAND_0_36", "reportNo": "CS-HTTP-4",
                     "coverageLowerMonths": 0, "coverageUpperMonths": 30,
                     "conclusionSupported": true, "conclusionText": "结论支持0-30月使用安全",
                     "labelText": "适用于0-30个月婴幼儿"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/filings/products/P-HC-4/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filing.changeType").value("SUBSTANTIVE"))
                .andExpect(jsonPath("$.filing.filingNo").value("SYN-F-000002"))
                .andExpect(jsonPath("$.filing.ageUpperMonths").value(30));

        // 缺 expectedVersion 的儿童变更 → 400
        mockMvc.perform(post("/api/filings/products/P-HC-4/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-HC-4C", "packagingText": "再次包装"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
