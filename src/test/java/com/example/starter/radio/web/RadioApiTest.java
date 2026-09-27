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

    @Test
    void changeFlowOverHttp() throws Exception {
        long calibratorId = postAndGetId("/api/calibrators",
                "{\"code\":\"CAL-CHG-1\",\"name\":\"合成活度计\"}");
        mvc.perform(post("/api/calibrators/daily-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-CHK\",\"calibratorId\":" + calibratorId
                                + ",\"checkDate\":\"2026-09-26\",\"referenceActivityBq\":1000.0,"
                                + "\"measuredActivityBq\":1000.0}"))
                .andExpect(status().isOk());
        long motherId = postAndGetId("/api/mother-liquors",
                "{\"batchCode\":\"ML-CHG-1\",\"nuclideCode\":\"SYN-1H\",\"initialActivityBq\":2200.0,"
                        + "\"referenceTime\":\"2026-09-26T00:00:00Z\"}");
        long prescriptionId = postAndGetId("/api/prescriptions",
                "{\"code\":\"RX-CHG-1\",\"nuclideCode\":\"SYN-1H\",\"prescribedActivityBq\":1000.0,"
                        + "\"administrationTime\":\"2026-09-26T01:00:00Z\"}");
        long dispensingId = postAndGetId("/api/dispensings/calculate",
                "{\"requestId\":\"REQ-CHG-CALC\",\"motherLiquorId\":" + motherId
                        + ",\"prescriptionId\":" + prescriptionId + ",\"calibratorId\":" + calibratorId + "}");
        mvc.perform(post("/api/dispensings/{id}/checks", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"alice\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/dispensings/{id}/checks", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"bob\"}"))
                .andExpect(status().isOk());

        // 提交候选：新时刻 T0+1.25h，活度 ~924.986，偏差 ~-7.5% 合法
        long changeId = postChangeId(
                "{\"requestId\":\"REQ-CHG-SUBMIT\",\"dispensingId\":" + dispensingId
                        + ",\"expectedVersion\":1,\"expectedPrescriptionVersion\":1,"
                        + "\"newAdministrationTime\":\"2026-09-26T01:15:00Z\"}");
        mvc.perform(get("/api/dispensings/changes/{id}", changeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.candidateVersionNo").value(2));

        // 同键异参 → 409
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-SUBMIT\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"expectedPrescriptionVersion\":1,"
                                + "\"newAdministrationTime\":\"2026-09-26T01:30:00Z\"}"))
                .andExpect(status().isConflict());
        // 参数非法（expectedVersion=0）→ 400
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-BAD\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":0,\"expectedPrescriptionVersion\":1,"
                                + "\"newAdministrationTime\":\"2026-09-26T01:15:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 已存在待复核候选时再次提交（含超偏差时刻 T0+2h）→ 422 业务拒绝
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-REJECT\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"expectedPrescriptionVersion\":1,"
                                + "\"newAdministrationTime\":\"2026-09-26T02:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 双人复核：单人重复 → 409
        mvc.perform(post("/api/dispensings/changes/{id}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"carol\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstReviewer").value("carol"));
        mvc.perform(post("/api/dispensings/changes/{id}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"carol\"}"))
                .andExpect(status().isConflict());
        // 第二名不同人员复核 → 生效
        mvc.perform(post("/api/dispensings/changes/{id}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 生效后分装版本=2、旧签认清除；版本证据两条
        mvc.perform(get("/api/dispensings/{id}", dispensingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.firstChecker").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.secondChecker").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.versions.length()").value(2));
        mvc.perform(get("/api/dispensings/{id}/versions", dispensingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].versionNo").value(2))
                .andExpect(jsonPath("$[1].changeOrderId").value(changeId));

        // 新版本未重新核对直接发药 → 422（旧签认不得复用）
        mvc.perform(post("/api/dispensings/dispense").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-DISP0\",\"dispensingId\":" + dispensingId
                                + ",\"remainingActivityBq\":0.0,\"measuredAt\":\"2026-09-26T01:15:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 证据查询含复核链
        mvc.perform(get("/api/dispensings/changes/{id}", changeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewChain.length()").value(2));

        // 变更单不存在 → 404
        mvc.perform(get("/api/dispensings/changes/{id}", 999999))
                .andExpect(status().isNotFound());
    }

    private long postChangeId(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private long postAndGetId(String url, String body) throws Exception {
        MvcResult result = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }
}
