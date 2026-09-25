package com.example.starter.damage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层测试：通过真实 REST 入口验证公开主流程与错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DamageCaseApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from case_event");
        jdbc.update("delete from reinspection");
        jdbc.update("delete from repair_completion");
        jdbc.update("delete from repair_approval");
        jdbc.update("delete from estimate");
        jdbc.update("delete from damage_item");
        jdbc.update("delete from damage_case");
        jdbc.update("delete from idempotency_record");
    }

    private String postJson(long caseId, String path, String body) throws Exception {
        return mvc.perform(post("/api/cases/{id}" + path, caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void fullRepairFlowOverHttp() throws Exception {
        String registerBody = """
                {"requestId":"reg-api-1","containerNo":"TEMU3000001","containerValue":10000.00}
                """;
        String registered = mvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("REGISTERED")))
                .andReturn().getResponse().getContentAsString();
        long caseId = Long.parseLong(registered.replaceAll(".*\"id\":(\\d+).*", "$1"));

        postJson(caseId, "/survey", """
                {"requestId":"svy-api-1","items":[
                  {"iiclCode":"DN","component":"侧板","description":"凹痕","responsibility":"OWNER","repairCost":1000.00},
                  {"iiclCode":"BR","component":"底梁","description":"弯曲","responsibility":"TERMINAL","repairCost":500.00}]}
                """);
        postJson(caseId, "/estimates", """
                {"requestId":"est-api-1"}
                """);
        postJson(caseId, "/approvals", """
                {"requestId":"ap-api-1","approvalDate":"2026-09-01"}
                """);
        postJson(caseId, "/repair-completions", """
                {"requestId":"cp-api-1","completionDate":"2026-09-07"}
                """);
        postJson(caseId, "/reinspections", """
                {"requestId":"ri-api-1","passed":true}
                """);
        postJson(caseId, "/close", """
                {"requestId":"cl-api-1"}
                """);

        mvc.perform(get("/api/cases/{id}", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseInfo.status", is("RELEASED")))
                .andExpect(jsonPath("$.caseInfo.determination", is("REPAIR")))
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.estimates.length()", is(1)))
                .andExpect(jsonPath("$.approvals.length()", is(1)))
                .andExpect(jsonPath("$.completions.length()", is(1)))
                .andExpect(jsonPath("$.reinspections.length()", is(1)));
    }

    @Test
    void registerValidationErrorReturns400() throws Exception {
        mvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"containerNo\":\"TEMU3000002\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCaseReturns404() throws Exception {
        mvc.perform(get("/api/cases/{id}", 999999))
                .andExpect(status().isNotFound());
    }

    @Test
    void idempotentReplayOverHttpReturnsSameBody() throws Exception {
        String body = """
                {"requestId":"reg-api-replay","containerNo":"TEMU3000003","containerValue":8000.00}
                """;
        String first = mvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(replay).isEqualTo(first);
    }
}
