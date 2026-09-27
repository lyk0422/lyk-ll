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
 * 铅封管理 HTTP 主流程与错误语义：400 参数非法 / 404 对象不存在 / 409 冲突 / 422 业务拒绝。
 */
@AutoConfigureMockMvc
class SealApiTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    private static final Instant START = T0.minus(java.time.Duration.ofHours(1));
    private static final Instant END = T0.plus(java.time.Duration.ofHours(10));

    private void registerSeal() throws Exception {
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-seal-1\",\"sealNo\":\"SEAL-0001\","
                                + "\"expectedVersion\":0,\"operator\":\"张三\"}"))
                .andExpect(status().isOk());
    }

    private void approveUnseal() throws Exception {
        mockMvc.perform(post("/api/valves/1/unseal-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"requestId\":\"req-unseal-1\",\"sealNo\":\"SEAL-0001\","
                                + "\"expectedVersion\":0,\"authorizerA\":\"李四\",\"authorizerB\":\"王五\","
                                + "\"windowStart\":\"%s\",\"windowEnd\":\"%s\",\"quotaTotal\":2}")
                                .formatted(START, END)))
                .andExpect(status().isOk());
    }

    private void submitImbalancedReadings() throws Exception {
        mockMvc.perform(post("/api/stations/1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readings":[
                                  {"unitId":1,"temperature":40.00,"recordedAt":"%s"},
                                  {"unitId":2,"temperature":42.50,"recordedAt":"%s"},
                                  {"unitId":3,"temperature":41.00,"recordedAt":"%s"},
                                  {"unitId":4,"temperature":41.00,"recordedAt":"%s"}
                                ]}
                                """.formatted(clock.instant(), clock.instant(),
                                clock.instant(), clock.instant())))
                .andExpect(status().isOk());
    }

    @Test
    void 登记批准调节撤销复封全流程() throws Exception {
        // 登记铅封
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-seal-1\",\"sealNo\":\"SEAL-0001\","
                                + "\"expectedVersion\":0,\"operator\":\"张三\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealNo").value("SEAL-0001"))
                .andExpect(jsonPath("$.valveVersion").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));

        // 完整铅封下调节令被拒绝（422）
        submitImbalancedReadings();
        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-adj-sealed\",\"unitId\":1,"
                                + "\"stepPercent\":5,\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity());

        // 批准解封（两名不同授权人、固定 UTC 窗口、额度 2）
        approveUnseal();

        // 受控调节成功：开度 50 → 55，消费一次额度
        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-adj-1\",\"unitId\":1,"
                                + "\"stepPercent\":5,\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.newOpening").value(55.00));

        // 额度轨迹：授予 + 消费
        mockMvc.perform(get("/api/valves/unseal-orders/1/quota-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("GRANT"))
                .andExpect(jsonPath("$[1].eventType").value("CONSUME"))
                .andExpect(jsonPath("$[1].quotaUsed").value(1));

        // 撤销解封（行版本 1 + 消费 1 = 2）
        mockMvc.perform(post("/api/valves/unseal-orders/1/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-revoke-1\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.version").value(3));

        // 复封：两名不同人员核对当前开度版本（1），登记新铅封号
        mockMvc.perform(post("/api/valves/1/reseal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-reseal-1\",\"sealNo\":\"SEAL-0002\","
                                + "\"expectedVersion\":1,\"confirmerA\":\"赵六\",\"confirmerB\":\"孙七\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealNo").value("SEAL-0002"))
                .andExpect(jsonPath("$.valveVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 历史封号链保留
        mockMvc.perform(get("/api/valves/1/seals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sealNo").value("SEAL-0001"))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].sealNo").value("SEAL-0002"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"));
    }

    @Test
    void 参数非法400对象不存在404() throws Exception {
        // 缺 sealNo → 400
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-bad\",\"expectedVersion\":0,\"operator\":\"张三\"}"))
                .andExpect(status().isBadRequest());
        // 缺 expectedVersion → 400
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-bad2\",\"sealNo\":\"SEAL-0001\",\"operator\":\"张三\"}"))
                .andExpect(status().isBadRequest());
        // 阀门不存在 → 404
        mockMvc.perform(post("/api/valves/99/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-nf\",\"sealNo\":\"SEAL-0099\","
                                + "\"expectedVersion\":0,\"operator\":\"张三\"}"))
                .andExpect(status().isNotFound());
        // 解封单不存在 → 404
        mockMvc.perform(post("/api/valves/unseal-orders/99/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-nf2\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/valves/unseal-orders/99/quota-events"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 同键异参409与业务拒绝422() throws Exception {
        registerSeal();
        // 同键异参 → 409
        mockMvc.perform(post("/api/valves/1/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-seal-1\",\"sealNo\":\"SEAL-0002\","
                                + "\"expectedVersion\":0,\"operator\":\"张三\"}"))
                .andExpect(status().isConflict());
        // 同一授权人 → 422
        mockMvc.perform(post("/api/valves/1/unseal-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"requestId\":\"req-u-same\",\"sealNo\":\"SEAL-0001\","
                                + "\"expectedVersion\":0,\"authorizerA\":\"李四\",\"authorizerB\":\"李四\","
                                + "\"windowStart\":\"%s\",\"windowEnd\":\"%s\",\"quotaTotal\":2}")
                                .formatted(START, END)))
                .andExpect(status().isUnprocessableEntity());
        // 额度超过两次 → 422
        mockMvc.perform(post("/api/valves/1/unseal-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"requestId\":\"req-u-q3\",\"sealNo\":\"SEAL-0001\","
                                + "\"expectedVersion\":0,\"authorizerA\":\"李四\",\"authorizerB\":\"王五\","
                                + "\"windowStart\":\"%s\",\"windowEnd\":\"%s\",\"quotaTotal\":3}")
                                .formatted(START, END)))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：修正参数后同键可成功
        mockMvc.perform(post("/api/valves/1/unseal-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"requestId\":\"req-u-q3\",\"sealNo\":\"SEAL-0001\","
                                + "\"expectedVersion\":0,\"authorizerA\":\"李四\",\"authorizerB\":\"王五\","
                                + "\"windowStart\":\"%s\",\"windowEnd\":\"%s\",\"quotaTotal\":2}")
                                .formatted(START, END)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaTotal").value(2));
    }

    @Test
    void 撤销与复封后调节均被拒绝() throws Exception {
        registerSeal();
        approveUnseal();
        // 撤销
        mockMvc.perform(post("/api/valves/unseal-orders/1/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-revoke-1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // 撤销后调节 → 422
        submitImbalancedReadings();
        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-adj-rev\",\"unitId\":1,"
                                + "\"stepPercent\":5,\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity());
        // 复封（当前开度版本 0）
        mockMvc.perform(post("/api/valves/1/reseal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-reseal-1\",\"sealNo\":\"SEAL-0002\","
                                + "\"expectedVersion\":0,\"confirmerA\":\"赵六\",\"confirmerB\":\"孙七\"}"))
                .andExpect(status().isOk());
        // 复封后调节 → 422
        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-adj-res\",\"unitId\":1,"
                                + "\"stepPercent\":5,\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
