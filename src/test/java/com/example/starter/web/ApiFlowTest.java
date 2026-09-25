package com.example.starter.web;

import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开 HTTP 主流程测试：申请授权（201/幂等 200/异参 409/超载 422/冲突 409）、
 * 位姿上报、越限与制动查询、释放。
 */
@AutoConfigureMockMvc
class ApiFlowTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private static final String T0 = "2026-09-25T10:00:00Z";
    private static final String T1 = "2026-09-25T11:00:00Z";

    private static String authBody(String requestId, String craneId, String start, String end, String load) {
        return "{\"requestId\":\"" + requestId + "\",\"craneId\":\"" + craneId
                + "\",\"startTime\":\"" + start + "\",\"endTime\":\"" + end
                + "\",\"loadTonnes\":" + load + "}";
    }

    @Test
    void fullAuthorizationPoseBrakeAndReleaseFlow() throws Exception {
        // 1. 申请授权：201
        mvc.perform(post("/api/zones/ZONE-1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody("req-1", "CRANE-A", T0, T1, "5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 2. 同键同参重放：200 且返回首次授权
        mvc.perform(post("/api/zones/ZONE-1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody("req-1", "CRANE-A", T0, T1, "5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.id").value(1));

        // 3. 同键异参：409
        mvc.perform(post("/api/zones/ZONE-1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody("req-1", "CRANE-A", T0, T1, "6")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_MISMATCH"));

        // 4. 同时段冲突：409
        mvc.perform(post("/api/zones/ZONE-1/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody("req-2", "CRANE-B", T0, T1, "5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ZONE_CONFLICT"));

        // 5. 超载：422
        mvc.perform(post("/api/zones/ZONE-2/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody("req-3", "CRANE-B", T0, T1, "9")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERLOAD"));

        // 6. 授权窗口内位姿：不触发预警
        mvc.perform(post("/api/cranes/CRANE-A/poses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestamp\":\"2026-09-25T10:30:00Z\",\"hookX\":1.0,\"hookY\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.breachEvents", hasSize(0)));

        // 7. CRANE-B 未授权进入 ZONE-1，持续 3 秒触发制动
        mvc.perform(post("/api/cranes/CRANE-B/poses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestamp\":\"2026-09-25T10:00:00Z\",\"hookX\":1.0,\"hookY\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breachEvents", hasSize(1)))
                .andExpect(jsonPath("$.breachEvents[0].status").value("OPEN"));
        mvc.perform(post("/api/cranes/CRANE-B/poses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestamp\":\"2026-09-25T10:00:03Z\",\"hookX\":1.0,\"hookY\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brakeCommands", hasSize(1)))
                .andExpect(jsonPath("$.breachEvents[0].status").value("BRAKED"));

        // 8. 查询越限事件与制动指令
        mvc.perform(get("/api/cranes/CRANE-B/breach-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("BRAKED"));
        mvc.perform(get("/api/cranes/CRANE-B/brake-commands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // 9. 释放授权
        mvc.perform(post("/api/authorizations/1/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"COMPLETE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void unknownCraneOrZoneReturns404() throws Exception {
        mvc.perform(post("/api/zones/ZONE-9/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody("req-1", "CRANE-A", T0, T1, "5")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/cranes/CRANE-9/poses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestamp\":\"" + T0 + "\",\"hookX\":1.0,\"hookY\":1.0}"))
                .andExpect(status().isNotFound());
    }
}
