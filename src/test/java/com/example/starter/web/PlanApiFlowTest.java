package com.example.starter.web;

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

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.TestInfraConfiguration;

/**
 * 公开 HTTP 接口主流程测试：申请 -> 编排 -> 确认 -> 执行 -> 核销，
 * 以及幂等重放、同键异参 409、参数校验 400、资源不存在 404。
 */
@AutoConfigureMockMvc
class PlanApiFlowTest extends AbstractIntegrationTest {

    private static final String BASE = TestInfraConfiguration.BASE_TIME.toLocalDate().toString();

    @Autowired
    private MockMvc mockMvc;

    private MvcResult postJson(String url, String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    @Test
    void 编排到核销全流程HTTP主流程() throws Exception {
        MvcResult applied = postJson("/api/pilot-plans", """
                {"requestId":"api-flow-1","planNo":"PLAN-API-1","shipName":"合成货轮","voyageNo":"V001"}
                """, 201);
        String id = com.jayway.jsonpath.JsonPath.read(applied.getResponse().getContentAsString(), "$.id")
                .toString();

        postJson("/api/pilot-plans/" + id + "/arrange", """
                {"requestId":"api-flow-2","pilotId":1,"tugboatIds":[1,2],
                 "tideStart":"%sT08:00:00","tideEnd":"%sT10:00:00"}
                """.formatted(BASE, BASE), 200);

        postJson("/api/pilot-plans/" + id + "/confirm", """
                {"requestId":"api-flow-3"}
                """, 200);

        postJson("/api/pilot-plans/" + id + "/start", """
                {"requestId":"api-flow-4"}
                """, 200);

        mockMvc.perform(post("/api/pilot-plans/" + id + "/settle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-flow-5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.tugboatIds[0]").value(1))
                .andExpect(jsonPath("$.tugboatIds[1]").value(2));

        mockMvc.perform(get("/api/pilot-plans/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planNo").value("PLAN-API-1"))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.breachCount").value(0));
    }

    @Test
    void 幂等重放返回首次结果且同键异参409() throws Exception {
        String body = """
                {"requestId":"api-idem-1","planNo":"PLAN-API-2","shipName":"合成货轮","voyageNo":"V001"}
                """;
        MvcResult first = postJson("/api/pilot-plans", body, 201);
        MvcResult replayed = postJson("/api/pilot-plans", body, 201);
        String firstId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id")
                .toString();
        String replayedId = com.jayway.jsonpath.JsonPath.read(replayed.getResponse().getContentAsString(), "$.id")
                .toString();
        org.junit.jupiter.api.Assertions.assertEquals(firstId, replayedId, "同键同参应返回首次结果");

        mockMvc.perform(post("/api/pilot-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-idem-1","planNo":"PLAN-API-3","shipName":"另一艘船","voyageNo":"V002"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void 参数缺失400与计划不存在404() throws Exception {
        mockMvc.perform(post("/api/pilot-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planNo\":\"PLAN-API-4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/pilot-plans/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 业务规则违反返回422() throws Exception {
        MvcResult applied = postJson("/api/pilot-plans", """
                {"requestId":"api-rule-1","planNo":"PLAN-API-5","shipName":"合成货轮","voyageNo":"V001"}
                """, 201);
        String id = com.jayway.jsonpath.JsonPath.read(applied.getResponse().getContentAsString(), "$.id")
                .toString();
        // 潮水窗口结束早于开始 -> 422
        mockMvc.perform(post("/api/pilot-plans/" + id + "/arrange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-rule-2","pilotId":1,"tugboatIds":[1],
                                 "tideStart":"%sT10:00:00","tideEnd":"%sT08:00:00"}
                                """.formatted(BASE, BASE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
    }
}
