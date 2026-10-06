package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP 层全流程：评级 -> 计划 -> 许可 -> 回传闭环 -> 台账；
 * 以及参数校验失败与幂等回放/冲突的 HTTP 语义。
 */
class ApiIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullFlowFromRatingToClosure() throws Exception {
        mockMvc.perform(post("/api/segments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-seg-1\",\"code\":\"SEG-API\",\"name\":\"演示路\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SEG-API"));

        MvcResult treeResult = mockMvc.perform(post("/api/trees").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-tree-1\",\"segmentCode\":\"SEG-API\","
                                + "\"treeCode\":\"T-API-1\",\"tiltDegrees\":30.0,\"diseaseLevel\":2,"
                                + "\"ageYears\":40,\"windExposure\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value(1))
                .andExpect(jsonPath("$.planId").isNumber())
                .andReturn();
        long planId = objectMapper.readTree(treeResult.getResponse().getContentAsString())
                .get("planId").asLong();

        MvcResult permitResult = mockMvc.perform(post("/api/permits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-permit-1\",\"segmentCode\":\"SEG-API\","
                                + "\"planId\":" + planId + ",\"workType\":\"HIGH_ALTITUDE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andReturn();
        String permitNo = objectMapper.readTree(permitResult.getResponse().getContentAsString())
                .get("permitNo").asText();

        mockMvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-report-1\",\"permitNo\":\"" + permitNo + "\","
                                + "\"beforePhoto\":\"photo-b-1\",\"afterPhoto\":\"photo-a-1\","
                                + "\"disposalRecord\":\"弃枝2车已清运至绿化处置场\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onTime").value(true));

        mockMvc.perform(get("/api/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].segmentCode").value("SEG-API"))
                .andExpect(jsonPath("$[0].level1Count").value(1))
                .andExpect(jsonPath("$[0].closedPlans").value(1))
                .andExpect(jsonPath("$[0].closureRate").value(1.0));
    }

    @Test
    void reportWithoutPhotosRejected() throws Exception {
        mockMvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-report-2\",\"permitNo\":\"PM-x\","
                                + "\"beforePhoto\":\"\",\"afterPhoto\":\"photo-a-1\","
                                + "\"disposalRecord\":\"弃枝已清运\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void idempotentReplayAndConflictOverHttp() throws Exception {
        String body = "{\"requestId\":\"api-seg-2\",\"code\":\"SEG-IDEM\",\"name\":\"幂等路\"}";
        MvcResult first = mockMvc.perform(post("/api/segments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(post("/api/segments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());
        assertEquals(firstJson, secondJson);

        String conflicting = "{\"requestId\":\"api-seg-2\",\"code\":\"SEG-OTHER\",\"name\":\"异参路\"}";
        mockMvc.perform(post("/api/segments").contentType(MediaType.APPLICATION_JSON)
                        .content(conflicting))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
}
