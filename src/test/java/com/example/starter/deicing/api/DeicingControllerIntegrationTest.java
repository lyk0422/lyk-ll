package com.example.starter.deicing.api;

import com.example.starter.deicing.AbstractDeicingIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口 H2 集成测试：验证 JSON 绑定、201/200/409/400/404 状态码与端到端业务结果。
 */
class DeicingControllerIntegrationTest extends AbstractDeicingIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    private MockMvc mvc() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        }
        return mockMvc;
    }

    private String applyBody(String requestId, String flight, String type, int bays, long departure)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "requestId", requestId,
                "flightNo", flight,
                "stand", "S1",
                "deiceType", type,
                "requiredBays", bays,
                "targetDepartureTs", departure));
    }

    @Test
    void applyReturns201AndReplayReturnsSameBodyWhileDifferentParamsReturn409() throws Exception {
        String body = applyBody("W1", "CA0001", "TWO_STEP", 1, BASE + 120 * MIN);

        mvc().perform(post("/api/deicing/requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("W1"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.deadlineTs").value(BASE + 90 * MIN));

        // 同键同参重放：返回首次 201
        mvc().perform(post("/api/deicing/requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"));

        // 同键异参：409 且队列不变
        String tampered = applyBody("W1", "CA0001", "TWO_STEP", 1, BASE + 180 * MIN);
        mvc().perform(post("/api/deicing/requests").contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidApplyBodyReturns400() throws Exception {
        mvc().perform(post("/api/deicing/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"flightNo\":\"X\",\"deiceType\":\"ONE_STEP\","
                                + "\"requiredBays\":0,\"targetDepartureTs\":" + (BASE + 10) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void dispatchStartCompleteAndDepartOverHttpWithHotBoundary() throws Exception {
        mvc().perform(post("/api/deicing/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("W2", "CA0002", "TWO_STEP", 1, BASE + 60 * MIN)))
                .andExpect(status().isCreated());

        mvc().perform(post("/api/deicing/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"W2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.assignments[0].startTs").value(BASE))
                .andExpect(jsonPath("$.assignments[0].endTs").value(BASE + 30 * MIN));

        mvc().perform(post("/api/deicing/work/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"W2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        clock.advance(30 * MIN);
        mvc().perform(post("/api/deicing/work/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"W2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEICED"))
                .andExpect(jsonPath("$.hotExpiresTs").value(BASE + 60 * MIN));

        // 恰在 HOT 终点起飞有效
        mvc().perform(post("/api/deicing/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"W2\",\"actualDepartureTs\":" + (BASE + 60 * MIN) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTED"));

        // 终态后迟到的异参起飞回报不复活
        mvc().perform(post("/api/deicing/depart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"W2\",\"actualDepartureTs\":" + (BASE + 90 * MIN) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPARTED"));
    }

    @Test
    void dispatchingUnknownRequestReturns404() throws Exception {
        mvc().perform(post("/api/deicing/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"NOPE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void snapshotAndTimelineAreQueryable() throws Exception {
        mvc().perform(post("/api/deicing/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("W3", "CA0003", "TWO_STEP", 2, BASE + 200 * MIN)))
                .andExpect(status().isCreated());
        mvc().perform(post("/api/deicing/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"W3\"}"))
                .andExpect(status().isOk());

        mvc().perform(get("/api/deicing/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].requestId").value("W3"));

        mvc().perform(get("/api/deicing/timeline").param("bayId", "B1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value("W3"))
                .andExpect(jsonPath("$[0].bayId").value("B1"));
    }
}
