package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 铅封管理 HTTP 主流程：登记 → 普通调节令拒绝 → 解封批准 → 受控调节 →
 * 额度轨迹 → 撤销 → 复封 → 封号链；含 400/404/409/422 错误语义。
 */
@AutoConfigureMockMvc
class SealApiTest extends IntegrationTestBase {

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
    void 铅封解封受控调节复封全流程() throws Exception {
        // 温度上报：U1 欠流
        mockMvc.perform(post("/api/stations/1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingsBody(clock.instant())))
                .andExpect(status().isOk());

        // 铅封登记：版本从 1 开始
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-seal-1\",\"sealNo\":\"SEAL-001\",\"valveVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.valveVersion").value(0));

        // 完整铅封下普通调节令被拒绝（422）
        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-adj-sealed\",\"unitId\":1,\"stepPercent\":5}"))
                .andExpect(status().isUnprocessableEntity());

        // 解封批准：两名不同授权人，固定 UTC 半开窗口，额度 2
        mockMvc.perform(post("/api/seals/1/unseals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-unseal-1","expectedVersion":1,"valveVersion":0,
                                 "approverA":"张三","approverB":"李四",
                                 "windowStart":"2026-09-26T00:00:00Z","windowEnd":"2026-09-28T00:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.quotaTotal").value(2))
                .andExpect(jsonPath("$.quotaUsed").value(0));

        // 受控调节：原子更新开度、写版本并消费一次额度
        mockMvc.perform(post("/api/unseals/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-ca-1\",\"expectedVersion\":1,\"stepPercent\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.oldOpening").value(50.00))
                .andExpect(jsonPath("$.newOpening").value(55.00));

        // 额度轨迹：一次消费
        mockMvc.perform(get("/api/unseals/1/quota-usages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value("req-ca-1"));
        mockMvc.perform(get("/api/unseals/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaUsed").value(1))
                .andExpect(jsonPath("$.version").value(2));

        // 撤销：仅禁止新调节，不回拨开已改开度
        mockMvc.perform(post("/api/unseals/1/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-revoke-1\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.version").value(3));

        // 复封：两名不同人员核对当前开度版本（1），登记新铅封号
        mockMvc.perform(post("/api/valves/1/reseals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-reseal-1","expectedVersion":1,"newSealNo":"SEAL-002",
                                 "checkerA":"甲","checkerB":"乙","valveVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealNo").value("SEAL-002"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 封号链：旧铅封 RESEALED，新铅封 ACTIVE
        mockMvc.perform(get("/api/valves/1/seals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sealNo").value("SEAL-001"))
                .andExpect(jsonPath("$[0].status").value("RESEALED"))
                .andExpect(jsonPath("$[1].sealNo").value("SEAL-002"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"));
    }

    @Test
    void 错误语义_参数非法400_对象不存在404_同键异参409() throws Exception {
        // 参数非法 400：缺少 sealNo
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-bad\",\"valveVersion\":0}"))
                .andExpect(status().isBadRequest());

        // 对象不存在 404：阀门不存在
        mockMvc.perform(post("/api/valves/999/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-404\",\"sealNo\":\"SEAL-X\",\"valveVersion\":0}"))
                .andExpect(status().isNotFound());
        // 对象不存在 404：解封单不存在
        mockMvc.perform(get("/api/unseals/999"))
                .andExpect(status().isNotFound());

        // 同键异参 409
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-cf\",\"sealNo\":\"SEAL-C1\",\"valveVersion\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-cf\",\"sealNo\":\"SEAL-C2\",\"valveVersion\":0}"))
                .andExpect(status().isConflict());
        // 同键同参重放首次结果
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-cf\",\"sealNo\":\"SEAL-C1\",\"valveVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealNo").value("SEAL-C1"));
    }
}
