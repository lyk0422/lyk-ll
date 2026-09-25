package com.example.starter.survey.web;

import com.example.starter.survey.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 测试：真实 JSON 编解码、状态码与幂等响应。
 */
class SurveyControllerHttpTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mvc;
    }

    @Test
    void fullHappyPathOverHttpWithIdempotentReplayAndConflict() throws Exception {
        // 船舶种子。
        mvc().perform(get("/api/surveys/ships"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].imo").value("IMO9000001"));

        // 申请。
        mvc().perform(post("/api/surveys/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-apply","imo":"IMO9000001","typeCode":"ANNUAL"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.windowStart").value("2026-03-15"))
                .andExpect(jsonPath("$.windowEnd").value("2026-09-15"));

        // 同键异参 → 409，不产生新检验单（HTTP 层验证冲突语义）。
        mvc().perform(post("/api/surveys/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-apply","imo":"IMO9000002","typeCode":"ANNUAL"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 同键同参重放 → 200。
        mvc().perform(post("/api/surveys/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-apply","imo":"IMO9000001","typeCode":"ANNUAL"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        Long surveyId = jdbc.queryForObject(
                "SELECT id FROM survey WHERE apply_request_id = 'h-apply'", Long.class);

        // 登轮（恰窗口首日），登记一个一般缺陷。
        mvc().perform(post("/api/surveys/board")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-board","surveyId":%d,"boardDate":"2026-03-15",
                                 "defects":[{"severity":"GENERAL","description":"舷梯防滑条磨损"}]}"""
                                .formatted(surveyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECTIFYING"));

        // 窗口外登轮新检验 → 400。
        mvc().perform(post("/api/surveys/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-apply-2","imo":"IMO9000001","typeCode":"ANNUAL"}"""))
                .andExpect(status().isConflict()); // 前一检验仍进行中

        Long defectId = jdbc.queryForObject(
                "SELECT id FROM defect WHERE survey_id = ?", Long.class, surveyId);

        mvc().perform(post("/api/surveys/defects/rectify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-rect","defectId":%d,"rectifiedDate":"2026-03-20"}"""
                                .formatted(defectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECTIFIED"));

        // 附条件发证。
        mvc().perform(post("/api/surveys/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-issue","surveyId":%d,"issueDate":"2026-03-21"}"""
                                .formatted(surveyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.recheckDue").value("2026-04-14"));

        // 恰第 30 日复查（发现日 2026-03-15 + 30 日 = 2026-04-14）受理。
        mvc().perform(post("/api/surveys/defects/recheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-recheck","defectId":%d,"submitDate":"2026-04-14"}"""
                                .formatted(defectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defect.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.expiredCertificate").doesNotExist());

        // 展期申请 + 批准，恰 3 个月。
        mvc().perform(post("/api/surveys/extensions/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-ext-apply","imo":"IMO9000001","typeCode":"ANNUAL","months":3}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        Long extensionId = jdbc.queryForObject(
                "SELECT id FROM extension_application WHERE request_id = 'h-ext-apply'",
                Long.class);

        mvc().perform(post("/api/surveys/extensions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-ext-ok","extensionId":%d}""".formatted(extensionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.newValidUntil").value("2027-12-15"));

        // 有效证书已同事务延长。
        mvc().perform(get("/api/surveys/certificates/valid")
                        .param("imo", "IMO9000001").param("typeCode", "ANNUAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validUntil").value("2027-12-15"));

        // 第二次展期 → 409。
        mvc().perform(post("/api/surveys/extensions/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-ext-again","imo":"IMO9000001","typeCode":"ANNUAL","months":1}"""))
                .andExpect(status().isConflict());

        // 特检禁展期。
        mvc().perform(post("/api/surveys/extensions/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-ext-spec","imo":"IMO9000001","typeCode":"SPECIAL","months":3}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTypeReturnsBadRequestAndUnknownShipReturns404() throws Exception {
        mvc().perform(post("/api/surveys/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-bad-type","imo":"IMO9000001","typeCode":"WAT"}"""))
                .andExpect(status().isBadRequest());

        mvc().perform(get("/api/surveys/certificates/valid")
                        .param("imo", "IMO-NOPE").param("typeCode", "ANNUAL"))
                .andExpect(status().isNotFound());
    }
}
