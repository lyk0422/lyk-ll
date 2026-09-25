package com.example.starter.trial.api;

import com.example.starter.trial.AbstractTrialIntegrationTest;
import com.example.starter.trial.domain.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 经真实 HTTP 入口（MockMvc + 真实 H2 上下文）验证主流程与失败状态码。
 */
@AutoConfigureMockMvc
class TrialControllerWebTest extends AbstractTrialIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowEnrollBlindedQueryTwoPersonUnblindAndAuditThroughHttp() throws Exception {
        String enrollBody = """
                {
                  "requestId": "HTTP-1",
                  "screeningNo": "S-HTTP",
                  "centerCode": "%s",
                  "consentTimeUtc": "2026-06-01T01:23:45Z"
                }
                """.formatted(DataSeeder.CENTER_SHANGHAI);

        MvcResult enrolled = mockMvc.perform(post("/api/trial/subjects/enrollments")
                        .contentType(MediaType.APPLICATION_JSON).content(enrollBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningNo").value("S-HTTP"))
                .andExpect(jsonPath("$.randomNo").value(1))
                .andExpect(jsonPath("$.blindedCode").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("BLIND-A"), org.hamcrest.Matchers.is("BLIND-B"))))
                .andExpect(jsonPath("$.status").value("ENROLLED"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(
                enrolled.getResponse().getContentAsString().contains("blindedCode"));
        org.junit.jupiter.api.Assertions.assertFalse(
                enrolled.getResponse().getContentAsString().contains("TREATMENT"),
                "入组响应不得暴露真实组别");

        // 盲态查询：groupCode 为 null，不返回真实组别。
        MvcResult blinded = mockMvc.perform(get("/api/trial/subjects/S-HTTP/blinding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENROLLED"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertTrue(
                blinded.getResponse().getContentAsString().contains("\"groupCode\":null"),
                "未揭盲时 groupCode 必须为 null");
        org.junit.jupiter.api.Assertions.assertFalse(
                blinded.getResponse().getContentAsString().contains("TREATMENT"));
        org.junit.jupiter.api.Assertions.assertFalse(
                blinded.getResponse().getContentAsString().contains("CONTROL"));

        // 同一人确认两次 => 422，不生效。
        String singlePersonBody = """
                {"requestId": "HTTP-UB-BAD", "firstConfirmer": "%s", "secondConfirmer": "%s"}
                """.formatted(DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_LI);
        mockMvc.perform(post("/api/trial/subjects/S-HTTP/unblind")
                        .contentType(MediaType.APPLICATION_JSON).content(singlePersonBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));

        // 同键异参入组 => 409。
        String conflictBody = enrollBody.replace("2026-06-01T01:23:45Z", "2026-06-01T02:23:45Z");
        mockMvc.perform(post("/api/trial/subjects/enrollments")
                        .contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict());

        // 两名不同授权人揭盲 => 生效并返回真实组别。
        String unblindBody = """
                {"requestId": "HTTP-UB-1", "firstConfirmer": "%s", "secondConfirmer": "%s"}
                """.formatted(DataSeeder.UNBLINDER_DR_LI, DataSeeder.UNBLINDER_DR_WANG);
        mockMvc.perform(post("/api/trial/subjects/S-HTTP/unblind")
                        .contentType(MediaType.APPLICATION_JSON).content(unblindBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNBLINDED"))
                .andExpect(jsonPath("$.groupCode").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("TREATMENT"), org.hamcrest.Matchers.is("CONTROL"))));

        // 揭盲后盲态查询返回真实组别。
        mockMvc.perform(get("/api/trial/subjects/S-HTTP/blinding"))
                .andExpect(jsonPath("$.status").value("UNBLINDED"))
                .andExpect(jsonPath("$.groupCode").exists());

        // 审计事件按发生顺序为三条。
        mockMvc.perform(get("/api/trial/subjects/S-HTTP/unblind-audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventType").value("UNBLIND_FIRST_CONFIRMED"))
                .andExpect(jsonPath("$[1].eventType").value("UNBLIND_SECOND_CONFIRMED"))
                .andExpect(jsonPath("$[2].eventType").value("UNBLIND_EFFECTIVE"));
    }

    @Test
    void unknownSubjectAndMalformedRequestsReturnExpectedStatusCodes() throws Exception {
        mockMvc.perform(get("/api/trial/subjects/NOPE/blinding"))
                .andExpect(status().isNotFound());

        String invalidEnroll = """
                {"requestId": "", "screeningNo": "S1", "centerCode": "C-SH",
                 "consentTimeUtc": "2026-06-01T01:23:45Z"}
                """;
        mockMvc.perform(post("/api/trial/subjects/enrollments")
                        .contentType(MediaType.APPLICATION_JSON).content(invalidEnroll))
                .andExpect(status().isBadRequest());

        String enrollUnknownCenter = """
                {"requestId": "R-X", "screeningNo": "S1", "centerCode": "MARS",
                 "consentTimeUtc": "2026-06-01T01:23:45Z"}
                """;
        mockMvc.perform(post("/api/trial/subjects/enrollments")
                        .contentType(MediaType.APPLICATION_JSON).content(enrollUnknownCenter))
                .andExpect(status().isUnprocessableEntity());
    }
}
