package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层冒烟：采样接入、幂等重放与 409、参数校验、日评估接口。
 */
@AutoConfigureMockMvc
class ApiSmokeTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 采样接口主流程与幂等语义() throws Exception {
        String body = "{\"requestId\":\"req-api-1\",\"dissolvedOxygen\":4.0,"
                + "\"ammoniaNitrogen\":0.10,\"temperature\":20.0,"
                + "\"sampledAt\":\"2026-10-06T09:00:00\"}";
        MvcResult first = mockMvc.perform(post("/api/zones/ZONE-A/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violatedParams[0]").value("DO"))
                .andExpect(jsonPath("$.zoneStatus").value("ALARM"))
                .andReturn();

        // 同键同参重放：返回首次报文
        MvcResult replayed = mockMvc.perform(post("/api/zones/ZONE-A/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replayed.getResponse().getContentAsString());

        // 同键异参：409
        String conflict = body.replace("4.0", "4.5");
        mockMvc.perform(post("/api/zones/ZONE-A/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 缺少requestId返回400() throws Exception {
        mockMvc.perform(post("/api/zones/ZONE-A/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dissolvedOxygen\":6.0,\"ammoniaNitrogen\":0.1,\"temperature\":20.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 日评估接口返回各池结果() throws Exception {
        mockMvc.perform(get("/api/evaluation/daily").param("date", "2026-10-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].zoneCode").value("ZONE-A"))
                .andExpect(jsonPath("$[0].stableSeconds").value(86400));
    }
}
