package com.example.starter.roaming;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开 HTTP API 端到端测试（真实 H2 事务，非 mock 业务边界）：
 * 授权 → 启动 → 计量 → 结束 → 封存 → 迟到快照挂账 → 核销，并验证 409 幂等冲突。
 */
class RoamingHttpEndToEndTest extends AbstractRoamingTest {

    @Test
    void fullRoamingFlowOverHttp() throws Exception {
        // 1) 授权：车属 OP-A 的车在 OP-B 桩即插即充。
        String authBody = """
                {"requestId":"REQ-AUTH-1","certFingerprint":"FP-CAR-0001","pileNo":"PILE-B-001",
                 "requestTime":"2026-09-20T10:00:00Z"}""";
        String authResponse = mockMvc.perform(post("/api/roaming/authorizations")
                        .contentType(MediaType.APPLICATION_JSON).content(authBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-20T10:05:00Z"))
                .andReturn().getResponse().getContentAsString();
        String sessionNo = com.jayway.jsonpath.JsonPath.read(authResponse, "$.sessionNo");

        // 授权同键异参 → 409。
        String authConflict = authBody.replace("FP-CAR-0001", "FP-CAR-0002");
        mockMvc.perform(post("/api/roaming/authorizations")
                        .contentType(MediaType.APPLICATION_JSON).content(authConflict))
                .andExpect(status().isConflict());

        // 2) 启动充电。
        mockMvc.perform(post("/api/roaming/sessions/start")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"REQ-START-1","sessionNo":"%s"}""".formatted(sessionNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CHARGING"));

        // 3) 计量快照（乱序：先传序号1，再传序号0）。
        mockMvc.perform(post("/api/roaming/sessions/{no}/meter-snapshots", sessionNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"REQ-M-1","readingSeq":1,"energyKwh":10.000,
                                 "recordedAt":"2026-09-20T10:10:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalEnergyKwh").value(10.000));
        mockMvc.perform(post("/api/roaming/sessions/{no}/meter-snapshots", sessionNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"REQ-M-0","readingSeq":0,"energyKwh":0.000,
                                 "recordedAt":"2026-09-20T10:05:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalEnergyKwh").value(10.000));

        // 4) 结束会话（UTC 日 2026-09-20）。
        mockMvc.perform(post("/api/roaming/sessions/{no}/end", sessionNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"REQ-END-1","endTime":"2026-09-20T10:20:00Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.totalEnergyKwh").value(10.000));

        // 5) 按 UTC 日封存清分：10kWh，应收 10*1.2=12.00，应付 10*0.9=9.00。
        mockMvc.perform(post("/api/roaming/settlement/batches/seal")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"REQ-SEAL-1","settlementDay":"2026-09-20"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sessionCount").value(1))
                .andExpect(jsonPath("$.totalEnergyKwh").value(10.000))
                .andExpect(jsonPath("$.totalReceivable").value(12.00))
                .andExpect(jsonPath("$.totalPayable").value(9.00))
                .andExpect(jsonPath("$.details[0].receivableAmount").value(12.00))
                .andExpect(jsonPath("$.details[0].payableAmount").value(9.00));

        // 封存终态：重复封存返回首次清分单。
        mockMvc.perform(post("/api/roaming/settlement/batches/seal")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"REQ-SEAL-2","settlementDay":"2026-09-20"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 6) 封存后迟到快照 → 差异挂账，增量 5kWh（应收6.00，应付4.50）。
        String meterLate = mockMvc.perform(post("/api/roaming/sessions/{no}/meter-snapshots", sessionNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"REQ-M-LATE","readingSeq":2,"energyKwh":15.000,
                                 "recordedAt":"2026-09-20T23:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.suspended").value(true))
                .andReturn().getResponse().getContentAsString();
        String suspenseNo = com.jayway.jsonpath.JsonPath.read(meterLate, "$.suspenseNo");

        // 7) 人工核销挂账并留痕。
        mockMvc.perform(post("/api/roaming/settlement/suspenses/{no}/write-off", suspenseNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"REQ-WO-1","operatorName":"operator-li","remark":"补录差异核销"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WRITTEN_OFF"))
                .andExpect(jsonPath("$.energyDeltaKwh").value(5.000))
                .andExpect(jsonPath("$.receivableDelta").value(6.00))
                .andExpect(jsonPath("$.payableDelta").value(4.50));

        // 同一挂账重复核销 → 409。
        mockMvc.perform(post("/api/roaming/settlement/suspenses/{no}/write-off", suspenseNo)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"REQ-WO-2","operatorName":"operator-wang","remark":"重复核销"}"""))
                .andExpect(status().isConflict());
    }
}
