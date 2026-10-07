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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 层错误语义与主流程：409 幂等冲突、422 业务拒绝、404 不存在、400 参数非法。
 */
@AutoConfigureMockMvc
class RadioApiTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

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

    @Test
    void withdrawalFlowOverHttpKeepsMotherBalanceAndVoidsBothTasks() throws Exception {
        long calibratorId = postAndGetId("/api/calibrators",
                "{\"code\":\"CAL-WD-1\",\"name\":\"合成活度计\"}");
        mvc.perform(post("/api/calibrators/daily-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-WD-CHK\",\"calibratorId\":" + calibratorId
                                + ",\"checkDate\":\"2026-09-26\",\"referenceActivityBq\":1000.0,"
                                + "\"measuredActivityBq\":1000.0}"))
                .andExpect(status().isOk());
        long motherId = postAndGetId("/api/mother-liquors",
                "{\"batchCode\":\"ML-WD-1\",\"nuclideCode\":\"SYN-1H\",\"initialActivityBq\":2200.0,"
                        + "\"referenceTime\":\"2026-09-26T00:00:00Z\"}");
        long rx1 = postAndGetId("/api/prescriptions",
                "{\"code\":\"RX-WD-1\",\"nuclideCode\":\"SYN-1H\",\"prescribedActivityBq\":1000.0,"
                        + "\"administrationTime\":\"2026-09-26T01:00:00Z\"}");
        long rx2 = postAndGetId("/api/prescriptions",
                "{\"code\":\"RX-WD-2\",\"nuclideCode\":\"SYN-1H\",\"prescribedActivityBq\":1000.0,"
                        + "\"administrationTime\":\"2026-09-26T01:00:00Z\"}");
        long d1 = postAndGetId("/api/dispensings/calculate",
                "{\"requestId\":\"REQ-WD-CALC-1\",\"motherLiquorId\":" + motherId
                        + ",\"prescriptionId\":" + rx1 + ",\"calibratorId\":" + calibratorId + "}");
        long d2 = postAndGetId("/api/dispensings/calculate",
                "{\"requestId\":\"REQ-WD-CALC-2\",\"motherLiquorId\":" + motherId
                        + ",\"prescriptionId\":" + rx2 + ",\"calibratorId\":" + calibratorId + "}");

        String createBody = "{\"requestId\":\"REQ-WD-CREATE\",\"motherLiquorId\":" + motherId
                + ",\"clientTime\":\"2026-09-26T09:00:00Z\",\"reason\":\"合成批次整批撤回\""
                + ",\"items\":["
                + "{\"dispensingId\":" + d1 + ",\"expectedVersion\":1,\"reason\":\"任务一撤回\"},"
                + "{\"dispensingId\":" + d2 + ",\"expectedVersion\":1,\"reason\":\"任务二撤回\"}]}";
        long withdrawalId = postAndGetId("/api/withdrawals", createBody);
        // 同键同参重放
        mvc.perform(post("/api/withdrawals").contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(withdrawalId))
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键不同时刻 → 409
        mvc.perform(post("/api/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody.replace("09:00:00Z", "09:00:01Z")))
                .andExpect(status().isConflict());

        // 冻结清单查询：逐项冻结版本、原因，候选终结证据为空
        mvc.perform(get("/api/withdrawals/{id}", withdrawalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].frozenVersionNo").value(1))
                .andExpect(jsonPath("$.items[0].terminatedCandidateId").value(org.hamcrest.Matchers.nullValue()));

        // 双人批准：第一人后版本 2；旧版本再批准 → 409；第二人版本 2 生效
        mvc.perform(post("/api/withdrawals/{id}/approvals", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"approver\":\"carol\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2));
        mvc.perform(post("/api/withdrawals/{id}/approvals", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"approver\":\"dave\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/withdrawals/{id}/approvals", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"approver\":\"dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.versionNo").value(3));

        // 两份分装一起 VOID
        mvc.perform(get("/api/dispensings/{id}", d1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));
        mvc.perform(get("/api/dispensings/{id}", d2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));
        // 撤回后新发药拒绝
        mvc.perform(post("/api/dispensings/dispense").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-WD-DISP\",\"dispensingId\":" + d1
                                + ",\"remainingActivityBq\":0.0,\"measuredAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 已批准不能撤销
        mvc.perform(post("/api/withdrawals/{id}/cancel", withdrawalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-WD-CANCEL\",\"expectedVersion\":3}"))
                .andExpect(status().isUnprocessableEntity());

        // 母液余额不回加：基线计算未扣库存，撤回也不加量
        Double remaining = jdbc.queryForObject(
                "SELECT remaining_activity_bq FROM mother_liquor WHERE id = ?", Double.class, motherId);
        org.assertj.core.api.Assertions.assertThat(remaining).isEqualTo(2200.0);
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
