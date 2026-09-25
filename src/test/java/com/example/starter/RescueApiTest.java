package com.example.starter;

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

/**
 * HTTP 入口测试：接报/派单/超时升级接口的状态码与幂等语义。
 */
@AutoConfigureMockMvc
class RescueApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportDispatchAndEscalateOverHttp() throws Exception {
        mockMvc.perform(post("/api/elevators")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"EL-API\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        String reportBody = "{\"requestId\":\"r-api\",\"elevatorCode\":\"EL-API\","
                + "\"reportedAt\":\"2026-01-01T00:00:00Z\"}";
        MvcResult reportResult = mockMvc.perform(post("/api/rescue/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(reportBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andReturn();
        Number idValue = com.jayway.jsonpath.JsonPath.read(
                reportResult.getResponse().getContentAsString(), "$.id");
        long caseId = idValue.longValue();

        // 同键同参重放：返回首次结果
        mockMvc.perform(post("/api/rescue/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(reportBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(caseId));

        // 同键异参：409 且不改变状态
        mockMvc.perform(post("/api/rescue/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-api\",\"elevatorCode\":\"EL-API\","
                                + "\"reportedAt\":\"2026-01-01T00:05:00Z\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 参数校验失败：400
        mockMvc.perform(post("/api/rescue/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"elevatorCode\":\"EL-API\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/rescue/cases/" + caseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"d-api\",\"dispatcher\":\"调度员\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"));

        // 时钟推进到接报后恰 30 分钟，触发超时升级
        clock.set(T0.plus(30, java.time.temporal.ChronoUnit.MINUTES));
        mockMvc.perform(post("/api/rescue/escalations/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(caseId));

        mockMvc.perform(get("/api/rescue/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalated").value(true));

        mockMvc.perform(get("/api/rescue/cases/" + caseId + "/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }
}
