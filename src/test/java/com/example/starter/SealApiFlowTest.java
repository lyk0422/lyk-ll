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
 * 铅封/解封/复封 HTTP 主流程：登记 → 旧入口拒绝 → 批准 → 受控调节 → 轨迹 → 复封。
 */
@AutoConfigureMockMvc
class SealApiFlowTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = T0;
    private static final Instant WINDOW_END = T0.plus(Duration.ofHours(2));

    @Autowired
    private MockMvc mockMvc;

    private String readingsBody() {
        return """
                {"readings":[
                  {"unitId":1,"temperature":40.00,"recordedAt":"%s"},
                  {"unitId":2,"temperature":42.50,"recordedAt":"%s"},
                  {"unitId":3,"temperature":41.00,"recordedAt":"%s"},
                  {"unitId":4,"temperature":41.00,"recordedAt":"%s"}
                ]}
                """.formatted(clock.instant(), clock.instant(), clock.instant(), clock.instant());
    }

    @Test
    void 铅封解封受控调节复封全流程() throws Exception {
        // 铅封登记
        mockMvc.perform(post("/api/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-seal-1\",\"unitId\":1,"
                                + "\"sealCode\":\"SEAL-API-1\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealCode").value("SEAL-API-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.valveVersion").value(0));

        // 同键重放返回首次结果
        mockMvc.perform(post("/api/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-seal-1\",\"unitId\":1,"
                                + "\"sealCode\":\"SEAL-API-1\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        // 完整铅封下旧调节入口拒绝（422）
        mockMvc.perform(post("/api/stations/1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingsBody()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stations/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-old\",\"unitId\":1,\"stepPercent\":5}"))
                .andExpect(status().isUnprocessableEntity());

        // 解封批准：两名授权人 + 固定 UTC 窗口
        mockMvc.perform(post("/api/seals/unseals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"requestId\":\"req-api-unseal-1\",\"sealCode\":\"SEAL-API-1\","
                                + "\"expectedVersion\":0,\"approver1\":\"张三\",\"approver2\":\"李四\","
                                + "\"windowStart\":\"%s\",\"windowEnd\":\"%s\"}")
                                .formatted(WINDOW_START, WINDOW_END)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.quotaTotal").value(2))
                .andExpect(jsonPath("$.quotaUsed").value(0))
                .andExpect(jsonPath("$.version").value(1));

        // 受控调节：消费一次额度，开度 50 → 55
        mockMvc.perform(post("/api/stations/1/controlled-adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-cadj-1\",\"unitId\":1,\"permitId\":1,"
                                + "\"expectedVersion\":0,\"stepPercent\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.newOpening").value(55.00));

        // 同键重放不重复消费
        mockMvc.perform(post("/api/stations/1/controlled-adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-cadj-1\",\"unitId\":1,\"permitId\":1,"
                                + "\"expectedVersion\":0,\"stepPercent\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        // 额度轨迹：恰一条消费记录
        mockMvc.perform(get("/api/permits/1/quota-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quotaUsedAfter").value(1))
                .andExpect(jsonPath("$[0].valveVersion").value(1));
        mockMvc.perform(get("/api/permits/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaUsed").value(1))
                .andExpect(jsonPath("$.version").value(2));

        // 复封：两名核对人 + 当前版本 1 + 新铅封号
        mockMvc.perform(post("/api/seals/reseal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-reseal-1\",\"oldSealCode\":\"SEAL-API-1\","
                                + "\"expectedVersion\":1,\"newSealCode\":\"SEAL-API-2\","
                                + "\"verifier1\":\"赵六\",\"verifier2\":\"孙七\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealCode").value("SEAL-API-2"))
                .andExpect(jsonPath("$.valveVersion").value(1));

        // 封号链保留历史：旧 RESEALED + 新 ACTIVE
        mockMvc.perform(get("/api/units/1/seals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sealCode").value("SEAL-API-1"))
                .andExpect(jsonPath("$[0].status").value("RESEALED"))
                .andExpect(jsonPath("$[1].sealCode").value("SEAL-API-2"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"));

        // 复封后旧解封单已终结，受控调节拒绝（422）
        mockMvc.perform(post("/api/stations/1/controlled-adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-cadj-2\",\"unitId\":1,\"permitId\":1,"
                                + "\"expectedVersion\":1,\"stepPercent\":5}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 参数非法400对象不存在404业务拒绝422() throws Exception {
        // 缺 requestId：400
        mockMvc.perform(post("/api/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitId\":1,\"sealCode\":\"SEAL-API-9\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        // 单元不存在：404
        mockMvc.perform(post("/api/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-404\",\"unitId\":99,"
                                + "\"sealCode\":\"SEAL-API-9\",\"expectedVersion\":0}"))
                .andExpect(status().isNotFound());
        // 铅封号不存在：404
        mockMvc.perform(get("/api/seals/SEAL-MISSING"))
                .andExpect(status().isNotFound());
        // 解封单不存在：404
        mockMvc.perform(get("/api/permits/999"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/permits/999/quota-events"))
                .andExpect(status().isNotFound());

        // 同一授权人：400
        mockMvc.perform(post("/api/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-seal-10\",\"unitId\":1,"
                                + "\"sealCode\":\"SEAL-API-10\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/seals/unseals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"requestId\":\"req-api-unseal-10\",\"sealCode\":\"SEAL-API-10\","
                                + "\"expectedVersion\":0,\"approver1\":\"张三\",\"approver2\":\"张三\","
                                + "\"windowStart\":\"%s\",\"windowEnd\":\"%s\"}")
                                .formatted(WINDOW_START, WINDOW_END)))
                .andExpect(status().isBadRequest());
        // 版本不一致：422
        mockMvc.perform(post("/api/seals/unseals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"requestId\":\"req-api-unseal-11\",\"sealCode\":\"SEAL-API-10\","
                                + "\"expectedVersion\":5,\"approver1\":\"张三\",\"approver2\":\"李四\","
                                + "\"windowStart\":\"%s\",\"windowEnd\":\"%s\"}")
                                .formatted(WINDOW_START, WINDOW_END)))
                .andExpect(status().isUnprocessableEntity());
        // 同键异参：409
        mockMvc.perform(post("/api/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-seal-10\",\"unitId\":1,"
                                + "\"sealCode\":\"SEAL-API-OTHER\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
    }
}
