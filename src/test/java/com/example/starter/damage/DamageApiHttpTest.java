package com.example.starter.damage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 端到端 HTTP：状态码、409 幂等冲突、重放响应一致、迟到检验与 404。 */
@SpringBootTest
@AutoConfigureMockMvc
class DamageApiHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        for (String table : List.of("case_event", "repair_cycle", "estimate_item",
                "estimate_version", "damage_item", "survey", "idempotent_request",
                "damage_case")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
    }

    @Test
    @DisplayName("HTTP 主链路：201登记→检验→估价→批复→完工→复检→放行，重放返回同一结果")
    void httpHappyPathAndReplay() throws Exception {
        MvcResult regResult = mockMvc.perform(post("/api/damage-cases/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-REG-1","containerNo":"CSQHTTP0001",
                                 "containerValue":1000.00,"arrivedAtUtc":1780272000000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andReturn();
        String caseNo = com.jayway.jsonpath.JsonPath
                .read(regResult.getResponse().getContentAsString(), "$.caseNo");

        mockMvc.perform(post("/api/damage-cases/survey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-SUR-1","caseNo":"%s","surveyor":"检验员-王",
                                 "surveyedAtUtc":1780275600000,
                                 "items":[{"code":"IICL-4010","location":"左前角柱",
                                           "responsibility":"OWNER","quantity":1}]}
                                """.formatted(caseNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SURVEYED"));

        MvcResult est1 = mockMvc.perform(post("/api/damage-cases/estimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-EST-1","caseNo":"%s"}""".formatted(caseNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentEstimate.decision").value("REPAIR"))
                .andReturn();

        MvcResult est2 = mockMvc.perform(post("/api/damage-cases/estimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-EST-1","caseNo":"%s"}""".formatted(caseNo)))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(est2.getResponse().getContentAsString())
                .isEqualTo(est1.getResponse().getContentAsString());

        mockMvc.perform(post("/api/damage-cases/estimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-EST-1","caseNo":"CSQHTTP9999"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(post("/api/damage-cases/approve-repair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-APV-1","caseNo":"%s","approvedAtUtc":1780275600000}"""
                                .formatted(caseNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPAIR_APPROVED"));

        // 恰第 7 个自然日末刻完工。
        mockMvc.perform(post("/api/damage-cases/complete-repair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-CMP-1","caseNo":"%s","completedAtUtc":1780963199999}"""
                                .formatted(caseNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPAIR_COMPLETED"));

        mockMvc.perform(post("/api/damage-cases/reinspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-RIN-1","caseNo":"%s","passed":true,
                                 "reinspectedAtUtc":1780963200000}""".formatted(caseNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REINSPECT_PASSED"));

        mockMvc.perform(post("/api/damage-cases/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-REL-1","caseNo":"%s","releasedAtUtc":1781049600000}"""
                                .formatted(caseNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.closeOutcome").value("RELEASED"));

        mockMvc.perform(get("/api/damage-cases/" + caseNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.repairCycles[0].reinspectResult").value("PASS"));
    }

    @Test
    @DisplayName("HTTP 失败语义：校验失败400、重复登记409、案件不存在404")
    void httpFailureSemantics() throws Exception {
        mockMvc.perform(post("/api/damage-cases/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","containerNo":"CSQHTTP0002",
                                 "containerValue":1000.00}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String body = """
                {"requestId":"H-REG-2","containerNo":"CSQHTTP0002",
                 "containerValue":1000.00,"arrivedAtUtc":1780272000000}""";
        mockMvc.perform(post("/api/damage-cases/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/damage-cases/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("H-REG-2", "H-REG-3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_CASE_EXISTS"));

        mockMvc.perform(get("/api/damage-cases/DC-NO-SUCH-CASE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
