package com.example.starter.feed.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 真实入口测试：走完整 Spring MVC + H2 事务栈，验证状态码、409 语义与响应字段。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessTables() {
        jdbc.update("DELETE FROM compliance_alert");
        jdbc.update("DELETE FROM batch_medication");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM formula_item");
        jdbc.update("DELETE FROM formula_version");
        jdbc.update("DELETE FROM formula");
        jdbc.update("DELETE FROM farm_batch");
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void fullFlowOverHttp_submitApproveProduceSlaughterAnd409() throws Exception {
        String formulaCode = unique("F");
        String batchNo = unique("BATCH");
        String submitReqId = unique("REQ-S");

        // 建档
        mockMvc.perform(post("/api/feed/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchNo":"%s","species":"POULTRY"}""".formatted(batchNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 提交（恰等于上限 0.002）
        String submitBody = """
                {"requestId":"%s","formulaCode":"%s","targetSpecies":"POULTRY",
                 "items":[{"ingredientCode":"CORN","ratio":0.70},
                          {"ingredientCode":"AMOXICILLIN","ratio":0.002}]}"""
                .formatted(submitReqId, formulaCode);
        mockMvc.perform(post("/api/feed/formulas/submit")
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.medicated").value(true));

        // 批准
        mockMvc.perform(post("/api/feed/formulas/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","formulaCode":"%s","versionNo":1}"""
                                .formatted(unique("REQ-A"), formulaCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 投产：末次饲喂 2026-09-01T10:00Z，休药 7 天 → 最早合法出栏日 2026-09-09
        mockMvc.perform(post("/api/feed/production")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","batchNo":"%s","formulaCode":"%s",
                                 "lastFeedingTime":"2026-09-01T10:00:00Z"}"""
                                .formatted(unique("REQ-P"), batchNo, formulaCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earliestSlaughterDate").value("2026-09-09"))
                .andExpect(jsonPath("$.maxWithdrawalDays").value(7));

        // 休药期内出栏 → 业务拒绝（200 + accepted=false + 最早合法出栏日）
        mockMvc.perform(post("/api/feed/slaughter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","batchNo":"%s","applyTime":"2026-09-08T23:59:59Z"}"""
                                .formatted(unique("REQ-X"), batchNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.earliestSlaughterDate").value("2026-09-09"));

        // 恰满当日 24:00 出栏 → 成功
        mockMvc.perform(post("/api/feed/slaughter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","batchNo":"%s","applyTime":"2026-09-09T00:00:00Z"}"""
                                .formatted(unique("REQ-X2"), batchNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status").value("SLAUGHTERED"));

        // 同键异参 → 409，且响应体带错误码
        String conflictBody = """
                {"requestId":"%s","formulaCode":"%s","targetSpecies":"POULTRY",
                 "items":[{"ingredientCode":"CORN","ratio":0.60},
                          {"ingredientCode":"AMOXICILLIN","ratio":0.002}]}"""
                .formatted(submitReqId, formulaCode);
        mockMvc.perform(post("/api/feed/formulas/submit")
                        .contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 版本轨迹可复算
        mockMvc.perform(get("/api/feed/formulas/{code}/versions", formulaCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));

        // 批次查询：终态保持
        mockMvc.perform(get("/api/feed/batches/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SLAUGHTERED"));
    }

    @Test
    void rejectedFormulaOverHttpShowsReasons() throws Exception {
        String formulaCode = unique("F");
        mockMvc.perform(post("/api/feed/formulas/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","formulaCode":"%s","targetSpecies":"POULTRY",
                                 "items":[{"ingredientCode":"AMOXICILLIN","ratio":0.001},
                                          {"ingredientCode":"OXYTETRACYCLINE","ratio":0.001}]}"""
                                .formatted(unique("REQ-S"), formulaCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReasons[0]").value(org.hamcrest.Matchers.containsString("配伍禁忌")));
    }
}
