package com.example.starter.tailings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.tailings.support.IntegrationTestSupport;

/**
 * API 层验证：调洪演算 HTTP 主流程、同键重放、同键异参 409、参数校验 400。
 */
@AutoConfigureMockMvc
class CalculationApiTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    private String calcBody(String requestId, long pondId, String designFloodVolume) {
        return """
                {"requestId":"%s","pondId":%d,"currentLevel":190,"designFloodVolume":%s,
                 "dryBeachLength":60,"phreaticDepth":3}
                """.formatted(requestId, pondId, designFloodVolume);
    }

    @Test
    void calculateReplayAndConflictOverHttp() throws Exception {
        long pondId = createPond();
        String requestId = rid();
        MvcResult first = mockMvc.perform(post("/api/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calcBody(requestId, pondId, "900000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("MUST_DISCHARGE"))
                .andExpect(jsonPath("$.availableCapacity").value(800000.0))
                .andReturn();
        String calcId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(),
                "$.calcId").toString();

        // 同键同参：返回首次结果
        mockMvc.perform(post("/api/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calcBody(requestId, pondId, "900000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calcId").value(Long.parseLong(calcId)));

        // 同键异参：409
        mockMvc.perform(post("/api/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calcBody(requestId, pondId, "950000")))
                .andExpect(status().isConflict());

        // 可复算核对
        mockMvc.perform(get("/api/calculations/{id}/recheck", calcId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        mockMvc.perform(post("/api/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"pondId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingPondReturns404() throws Exception {
        mockMvc.perform(post("/api/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(calcBody(rid(), 999999L, "100")))
                .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flood_calc", Integer.class));
    }
}
