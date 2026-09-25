package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 主流程：温度上报 → 失衡判定 → 步进调节 → 稳定 4 小时 → 效果评估 → 归档。
 */
@AutoConfigureMockMvc
class ApiFlowTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    private String readingsBody(Instant recordedAt) {
        return """
                {"readings":[
                  {"unitId":1,"temperature":40.00,"recordedAt":"%s"},
                  {"unitId":2,"temperature":42.50,"recordedAt":"%s"},
                  {"unitId":3,"temperature":41.00,"recordedAt":"%s"},
                  {"unitId":4,"temperature":41.00,"recordedAt":"%s"}
                ]}
                """.formatted(recordedAt, recordedAt, recordedAt, recordedAt);
    }

    @Test
    void 失衡判定步进调节效果评估归档全流程() throws Exception {
        // 上报温度：U1 欠流、U2 过流，温差 2.5℃ 失衡
        mockMvc.perform(post("/api/stations/1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingsBody(clock.instant())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(4));

        // 失衡判定：温差 2.5℃ > 2℃
        mockMvc.perform(get("/api/stations/1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.diff").value(2.5))
                .andExpect(jsonPath("$.units[0].classification").value("UNDERFLOW"))
                .andExpect(jsonPath("$.units[1].classification").value("OVERFLOW"));

        // 步进调节：欠流单元开大 10%（恰 10% 合法），开度 50 → 60
        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-1\",\"unitId\":1,\"stepPercent\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.classification").value("UNDERFLOW"))
                .andExpect(jsonPath("$.oldOpening").value(50.00))
                .andExpect(jsonPath("$.newOpening").value(60.00));

        // 同键重放返回首次结果
        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-1\",\"unitId\":1,\"stepPercent\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        // 同键异参返回 409
        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-1\",\"unitId\":1,\"stepPercent\":9}"))
                .andExpect(status().isConflict());

        // 稳定期未满：评估拒绝（422）
        mockMvc.perform(post("/api/tasks/1/assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-asm\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 稳定恰满 4 小时：补测新温度后评估，温差降至 0.5℃ 判平衡
        clock.advance(Duration.ofHours(4));
        mockMvc.perform(post("/api/stations/1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readings":[
                                  {"unitId":1,"temperature":41.00,"recordedAt":"%s"},
                                  {"unitId":2,"temperature":41.50,"recordedAt":"%s"},
                                  {"unitId":3,"temperature":41.20,"recordedAt":"%s"},
                                  {"unitId":4,"temperature":41.00,"recordedAt":"%s"}
                                ]}
                                """.formatted(clock.instant(), clock.instant(),
                                clock.instant(), clock.instant())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/1/assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-asm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beforeDiff").value(2.5))
                .andExpect(jsonPath("$.afterDiff").value(0.5))
                .andExpect(jsonPath("$.balanced").value(true));

        // 归档为终态，重复归档幂等成功
        mockMvc.perform(post("/api/tasks/1/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(post("/api/tasks/1/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // 开度版本链可复算：第 1 版 50 → 60
        mockMvc.perform(get("/api/stations/units/1/valve-versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNo").value(1))
                .andExpect(jsonPath("$[0].oldOpening").value(50.00))
                .andExpect(jsonPath("$[0].newOpening").value(60.00));
    }

    @Test
    void 步进超过10个百分点返回422() throws Exception {
        mockMvc.perform(post("/api/stations/1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingsBody(clock.instant())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-step\",\"unitId\":1,\"stepPercent\":10.5}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
