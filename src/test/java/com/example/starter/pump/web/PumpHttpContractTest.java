package com.example.starter.pump.web;

import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 契约层：真实经过 DispatcherServlet 校验 202 受理、409 幂等冲突、400 参数错误。
 */
@AutoConfigureMockMvc
class PumpHttpContractTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void orangeWarning_returns202_andReplay_returns202() throws Exception {
        String body = """
                {"requestId":"h-1","zoneCode":"ZN","alertLevel":"ORANGE",
                 "alertTime":"2026-09-26T08:00:00"}
                """;
        mockMvc.perform(post("/api/warnings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.triggered").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 同键同参重放：仍 202，标记 replayed
        mockMvc.perform(post("/api/warnings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void sameKeyDifferentParams_returns409() throws Exception {
        String first = """
                {"requestId":"h-2","zoneCode":"ZN","alertLevel":"ORANGE",
                 "alertTime":"2026-09-26T08:00:00"}
                """;
        mockMvc.perform(post("/api/warnings").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isAccepted());

        String changed = """
                {"requestId":"h-2","zoneCode":"ZN","alertLevel":"RED",
                 "alertTime":"2026-09-26T08:00:00"}
                """;
        mockMvc.perform(post("/api/warnings").contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_CONFLICT"));
    }

    @Test
    void missingRequiredField_returns400() throws Exception {
        String bad = """
                {"requestId":"h-3","alertLevel":"ORANGE","alertTime":"2026-09-26T08:00:00"}
                """;
        mockMvc.perform(post("/api/warnings").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void yellowWarning_returns202_notTriggered() throws Exception {
        String body = """
                {"requestId":"h-4","zoneCode":"ZN","alertLevel":"YELLOW",
                 "alertTime":"2026-09-26T08:00:00"}
                """;
        mockMvc.perform(post("/api/warnings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.triggered").value(false))
                .andExpect(jsonPath("$.status").value("NOT_TRIGGERED"));
    }
}
