package com.example.starter.forestry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * API 层测试：公开主流程（申请→发证→确认→验收）与错误语义（409/404/400）。
 */
class ForestryApiTest extends ForestryTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("主流程：申请→发证→采伐确认→更新验收全部经 HTTP 完成")
    void fullFlowOverHttp() throws Exception {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        // 申请
        MvcResult appResult = mockMvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-1","plotCode":"P1","operatorId":"OP-1",
                                 "applyYear":2026,"volume":100}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn();
        long applicationId = objectMapper.readTree(appResult.getResponse().getContentAsString())
                .get("applicationId").asLong();

        // 发证
        MvcResult licResult = mockMvc.perform(post("/api/licenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"lic-1\",\"applicationId\":" + applicationId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.issueDate").value("2026-03-01"))
                .andExpect(jsonPath("$.expireDate").value("2026-05-29"))
                .andReturn();
        long licenseId = objectMapper.readTree(licResult.getResponse().getContentAsString())
                .get("licenseId").asLong();

        // 采伐确认
        MvcResult confirmResult = mockMvc.perform(post("/api/licenses/{id}/confirm", licenseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.regenerationDeadline").value("2027-12-31"))
                .andReturn();
        long taskId = objectMapper.readTree(confirmResult.getResponse().getContentAsString())
                .get("regenerationTaskId").asLong();

        // 更新验收（恰达 85 合格）
        mockMvc.perform(post("/api/regeneration-tasks/{id}/acceptance", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"acc-1\",\"passRate\":85.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASSED"))
                .andExpect(jsonPath("$.taskStatus").value("PASSED"));

        // 限额查询
        mockMvc.perform(get("/api/quotas").param("year", "2026").param("tier", "PINE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedVolume").value(100.00));
    }

    @Test
    @DisplayName("错误语义：同键异参 409、资源缺失 404、参数非法 400")
    void errorSemantics() throws Exception {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        mockMvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-1","plotCode":"P1","operatorId":"OP-1",
                                 "applyYear":2026,"volume":100}
                                """))
                .andExpect(status().isOk());

        // 同键异参 → 409 IDEMPOTENCY_CONFLICT
        mockMvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-1","plotCode":"P1","operatorId":"OP-1",
                                 "applyYear":2026,"volume":200}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 小班不存在 → 404
        mockMvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-2","plotCode":"NOPE","operatorId":"OP-1",
                                 "applyYear":2026,"volume":100}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 体积非法 → 400
        mockMvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-3","plotCode":"P1","operatorId":"OP-1",
                                 "applyYear":2026,"volume":-5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("逾期确认：HTTP 409 LICENSE_EXPIRED，证件作废且限额释放")
    void expiredConfirmOverHttp() throws Exception {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");
        var application = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("100"));
        var license = service.issueLicense("lic-1", application.applicationId());

        clock.setDate(java.time.LocalDate.of(2026, 5, 30));
        mockMvc.perform(post("/api/licenses/{id}/confirm", license.licenseId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LICENSE_EXPIRED"));

        mockMvc.perform(get("/api/licenses/{id}", license.licenseId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));
        mockMvc.perform(get("/api/quotas").param("year", "2026").param("tier", "PINE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedVolume").value(0.0));
    }
}
