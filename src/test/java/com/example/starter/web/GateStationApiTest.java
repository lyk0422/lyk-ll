package com.example.starter.web;

import com.example.starter.TestTableCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 边界测试：浓度检测主流程 200、同键同参重放 200、同键异参 409、参数校验 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GateStationApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestTableCleaner cleaner;

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    private static final String BASE = "/api/gate-station";

    private String odorantJson(String requestId, String concentration) {
        return """
                {
                  "stationCode": "ST01",
                  "requestId": "%s",
                  "detectedAt": "2026-09-25T12:00:00",
                  "gasFlowM3": 100,
                  "concentrationMgM3": %s
                }
                """.formatted(requestId, concentration);
    }

    @Test
    void detectThenReplayThenConflictOverHttp() throws Exception {
        mockMvc.perform(post(BASE + "/odorant/detect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(odorantJson("API-1", "20.000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualified").value(true))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        // 同键同参：200 幂等重放
        mockMvc.perform(post(BASE + "/odorant/detect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(odorantJson("API-1", "20.000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 同键异参：409
        mockMvc.perform(post(BASE + "/odorant/detect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(odorantJson("API-1", "9.000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        mockMvc.perform(post(BASE + "/odorant/detect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "",
                                  "detectedAt": "2026-09-25T12:00:00",
                                  "gasFlowM3": 100,
                                  "concentrationMgM3": 20.000
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
