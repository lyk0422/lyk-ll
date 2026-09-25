package com.example.starter.radio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 层错误语义与主流程：409 幂等冲突、422 业务拒绝、404 不存在、400 参数非法。
 */
@AutoConfigureMockMvc
class RadioApiTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullFlowOverHttp() throws Exception {
        long calibratorId = postAndGetId("/api/calibrators",
                "{\"code\":\"CAL-API-1\",\"name\":\"合成活度计\"}");
        // 日检恰 5% 通过
        mvc.perform(post("/api/calibrators/daily-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHK-1\",\"calibratorId\":" + calibratorId
                                + ",\"checkDate\":\"2026-09-26\",\"referenceActivityBq\":1000.0,"
                                + "\"measuredActivityBq\":1050.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.deviationRatio").value(0.05));
        // 同键异参 → 409
        mvc.perform(post("/api/calibrators/daily-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHK-1\",\"calibratorId\":" + calibratorId
                                + ",\"checkDate\":\"2026-09-26\",\"referenceActivityBq\":1000.0,"
                                + "\"measuredActivityBq\":1060.0}"))
                .andExpect(status().isConflict());

        long motherId = postAndGetId("/api/mother-liquors",
                "{\"batchCode\":\"ML-API-1\",\"nuclideCode\":\"SYN-1H\",\"initialActivityBq\":2200.0,"
                        + "\"referenceTime\":\"2026-09-26T00:00:00Z\"}");
        long prescriptionId = postAndGetId("/api/prescriptions",
                "{\"code\":\"RX-API-1\",\"nuclideCode\":\"SYN-1H\",\"prescribedActivityBq\":1000.0,"
                        + "\"administrationTime\":\"2026-09-26T01:00:00Z\"}");

        // 分装恰 10% 合法，衰变推算至给药时刻恰 1100 Bq
        long dispensingId = postAndGetId("/api/dispensings/calculate",
                "{\"requestId\":\"REQ-CALC-1\",\"motherLiquorId\":" + motherId
                        + ",\"prescriptionId\":" + prescriptionId + ",\"calibratorId\":" + calibratorId + "}");
        mvc.perform(post("/api/dispensings/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CALC-1\",\"motherLiquorId\":" + motherId
                                + ",\"prescriptionId\":" + prescriptionId + ",\"calibratorId\":" + calibratorId
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dispensingId))
                .andExpect(jsonPath("$.dispensedActivityBq").value(1100.0))
                .andExpect(jsonPath("$.deviationRatio").value(0.1))
                .andExpect(jsonPath("$.status").value("CALCULATED"));

        // 未核对直接发药 → 422
        mvc.perform(post("/api/dispensings/dispense").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-DISP-0\",\"dispensingId\":" + dispensingId
                                + ",\"remainingActivityBq\":0.0,\"measuredAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 双人核对：同一人第二次 → 409
        mvc.perform(post("/api/dispensings/{id}/checks", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstChecker").value("alice"));
        mvc.perform(post("/api/dispensings/{id}/checks", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"alice\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/dispensings/{id}/checks", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED"));

        // 勾稽不平衡 → 422，库存不变
        mvc.perform(post("/api/dispensings/dispense").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-DISP-1\",\"dispensingId\":" + dispensingId
                                + ",\"remainingActivityBq\":500.0,\"measuredAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 勾稽平衡发药：2200 = 1100 + 0 + 1100
        mvc.perform(post("/api/dispensings/dispense").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-DISP-2\",\"dispensingId\":" + dispensingId
                                + ",\"remainingActivityBq\":0.0,\"measuredAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPENSED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.ledger.balanced").value(true))
                .andExpect(jsonPath("$.ledger.decayResidualBq").value(1100.0));
        // 终态重复完成 → 幂等成功
        mvc.perform(post("/api/dispensings/dispense").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-DISP-2\",\"dispensingId\":" + dispensingId
                                + ",\"remainingActivityBq\":0.0,\"measuredAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        // 迟到测量数据只记迟到事件
        mvc.perform(post("/api/dispensings/{id}/measurements", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payload\":\"late=0.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("LATE_RECORDED"));
        // 台账复算
        mvc.perform(get("/api/dispensings/{id}/reconciliation", dispensingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.dispensedTotalBq").value(1100.0));
    }

    @Test
    void notFoundAndBadRequestSemantics() throws Exception {
        mvc.perform(get("/api/dispensings/{id}", 999999))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/calibrators").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private long postAndGetId(String url, String body) throws Exception {
        MvcResult result = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }
}
