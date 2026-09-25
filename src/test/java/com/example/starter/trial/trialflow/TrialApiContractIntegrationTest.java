package com.example.starter.trial.trialflow;

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
 * 经真实 HTTP 入口（Controller + JSON + H2）验证完整合成业务链路：
 * 入组 → 盲态查询 → 同键重放/异参 409 → 双人揭盲（403/422/终态幂等）→ 审计查询 → 访视排除。
 */
@AutoConfigureMockMvc
class TrialApiContractIntegrationTest extends AbstractTrialIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullSyntheticJourneyThroughHttpApi() throws Exception {
        String base = "/api/trials";
        String trial = "CT-HTTP-1";
        String screen = "S-HTTP-1";

        // 1) 入组 201
        MvcResult created = mockMvc.perform(post(base + "/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-REQ-1","trialId":"%s","subjectScreenNo":"%s",
                                 "siteCode":"SITE-01","consentAtUtc":"2026-03-01T05:30:00Z"}
                                """.formatted(trial, screen)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.randomizationNo").value(1))
                .andExpect(jsonPath("$.blindedGroupCode").value(org.hamcrest.Matchers.in(java.util.List.of("A", "B"))))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String blindedCode = created.getResponse().getContentAsString()
                .replaceAll(".*\"blindedGroupCode\":\"([AB])\".*", "$1");

        // 2) 同键同参重放 → 200，replayed=true，随机号不变
        mockMvc.perform(post(base + "/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-REQ-1","trialId":"%s","subjectScreenNo":"%s",
                                 "siteCode":"SITE-01","consentAtUtc":"2026-03-01T05:30:00Z"}
                                """.formatted(trial, screen)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.randomizationNo").value(1))
                .andExpect(jsonPath("$.replayed").value(true));

        // 3) 同键异参 → 409
        mockMvc.perform(post(base + "/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-REQ-1","trialId":"%s","subjectScreenNo":"%s",
                                 "siteCode":"SITE-01","consentAtUtc":"2026-03-01T05:30:01Z"}
                                """.formatted(trial, screen)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 4) 参数校验失败 → 400（缺 requestId）
        mockMvc.perform(post(base + "/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trialId":"%s","subjectScreenNo":"%s",
                                 "siteCode":"SITE-01","consentAtUtc":"2026-03-01T05:30:00Z"}
                                """.formatted(trial, screen)))
                .andExpect(status().isBadRequest());

        // 5) 盲态查询 200（不暴露真实组别）；不存在 404
        mockMvc.perform(get(base + "/{trial}/subjects/{screen}", trial, screen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENROLLED"))
                .andExpect(jsonPath("$.blindedGroupCode").value(blindedCode))
                .andExpect(jsonPath("$.assignedGroup").doesNotExist());
        mockMvc.perform(get(base + "/{trial}/subjects/{screen}", trial, "GHOST"))
                .andExpect(status().isNotFound());

        // 6) 揭盲：非授权人 403；第一人后 PENDING；同人再确认 422；第二人后 COMPLETED
        clock.set(java.time.Instant.parse("2026-03-12T10:15:30Z"));
        mockMvc.perform(post(base + "/unblindings/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("H-UB", trial, screen, "HACKER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(base + "/unblindings/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("H-UB", trial, screen, "INV-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.unblindedGroupCode").doesNotExist());

        mockMvc.perform(post(base + "/unblindings/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("H-UB", trial, screen, "INV-001")))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post(base + "/unblindings/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("H-UB", trial, screen, "INV-002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.unblindedGroupCode")
                        .value("A".equals(blindedCode) ? "TREATMENT" : "CONTROL"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 7) 终态重放：同 requestId 再确认 → COMPLETED 且 replayed=true
        mockMvc.perform(post(base + "/unblindings/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("H-UB", trial, screen, "INV-003")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.confirmerA").value("INV-001"))
                .andExpect(jsonPath("$.confirmerB").value("INV-002"));

        // 8) 受试者已揭盲；审计事件恰为三条且按发生顺序
        mockMvc.perform(get(base + "/{trial}/subjects/{screen}", trial, screen))
                .andExpect(jsonPath("$.status").value("UNBLINDED"));
        mockMvc.perform(get(base + "/{trial}/subjects/{screen}/audit-events", trial, screen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventType").value("FIRST_CONFIRMED"))
                .andExpect(jsonPath("$[1].eventType").value("SECOND_CONFIRMED"))
                .andExpect(jsonPath("$[2].eventType").value("UNBLINDED"));

        // 9) 揭盲后新访视（默认时钟 2026-03-10 之后登记、且晚于揭盲日界）自动排除
        clock.set(java.time.Instant.parse("2026-03-12T10:15:30Z"));
        mockMvc.perform(post(base + "/{trial}/subjects/{screen}/visits", trial, screen)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visitAtUtc":"2026-03-13T09:00:00Z","visitType":"WEEK_3","measureValue":14.0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.excludedFromMainAnalysis").value(true))
                .andExpect(jsonPath("$.exclusionReason").value("SUBJECT_UNBLINDED"));
    }

    private String confirmBody(String requestId, String trial, String screen, String user) {
        return """
                {"requestId":"%s","trialId":"%s","subjectScreenNo":"%s","confirmerUserId":"%s"}
                """.formatted(requestId, trial, screen, user);
    }
}
