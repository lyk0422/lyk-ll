package com.example.starter.fishery;

import com.example.starter.fishery.init.SyntheticDataInitializer;
import org.junit.jupiter.api.AfterEach;
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
 * REST/JSON 入口测试：验证 201 受理、409 同键异参与 HTTP 错误语义（真实 H2 + 完整 Spring 栈）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FisheryWebTest extends AbstractFisheryTest {

    @Autowired
    private MockMvc mockMvc;

    private String vessel;

    @BeforeEach
    void setUp() {
        vessel = registerVesselWithQuota(SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void departureEndpoint_acceptsThenReplaysAndRejectsMismatchOverHttp() throws Exception {
        String requestId = newRequestId();
        String body = """
                {
                  "requestId": "%s",
                  "tripCode": "T-WEB-1",
                  "vesselCode": "%s",
                  "departAt": "2026-03-14T01:00:00Z",
                  "lines": [{"speciesCode": "HAIRTAIL", "weightKg": 40.000}]
                }
                """.formatted(requestId, vessel);

        mockMvc.perform(post("/api/fishery/departures")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DEPARTED"))
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.replayed").value(false));

        // 同键同参重放：返回首次结果（同一资源受理），replayed=true
        mockMvc.perform(post("/api/fishery/departures")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));

        // 同键异参：409
        String mismatch = body.replace("40.000", "41.000");
        mockMvc.perform(post("/api/fishery/departures")
                        .contentType(MediaType.APPLICATION_JSON).content(mismatch))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void departureOverQuotaReturns422() throws Exception {
        String body = """
                {
                  "requestId": "%s",
                  "tripCode": "T-WEB-2",
                  "vesselCode": "%s",
                  "departAt": "2026-03-14T01:00:00Z",
                  "lines": [{"speciesCode": "HAIRTAIL", "weightKg": 100.001}]
                }
                """.formatted(newRequestId(), vessel);
        mockMvc.perform(post("/api/fishery/departures")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超过配额")));
    }

    @Test
    void invalidJsonReturns400() throws Exception {
        String body = """
                {
                  "requestId": "%s",
                  "tripCode": "T-WEB-3",
                  "vesselCode": "%s",
                  "departAt": "2026-03-14T01:00:00Z",
                  "lines": []
                }
                """.formatted(newRequestId(), vessel);
        mockMvc.perform(post("/api/fishery/departures")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
