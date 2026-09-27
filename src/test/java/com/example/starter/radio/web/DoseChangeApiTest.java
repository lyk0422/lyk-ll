package com.example.starter.radio.web;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 给药时刻变更复核流程的 HTTP 语义：候选提交、双人复核准入、版本/候选证据查询，
 * 以及 400 参数非法、404 不存在、409 幂等/版本冲突、422 业务拒绝。
 */
@AutoConfigureMockMvc
class DoseChangeApiTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Test
    void changeFlowOverHttp() throws Exception {
        long dispensingId = newCheckedDispensing();

        // 提交候选：版本 1 → 候选版本 2
        mvc.perform(post("/api/dispensings/{id}/changes", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-1\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"2026-09-26T01:05:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SUBMITTED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.change.candidateVersion").value(2))
                .andExpect(jsonPath("$.change.status").value("PENDING"))
                // 候选期间有效分装不变
                .andExpect(jsonPath("$.dispensing.version").value(1))
                .andExpect(jsonPath("$.dispensing.status").value("CHECKED"));

        long changeId = extractChangeId(mvc.perform(get("/api/dispensings/{id}/changes", dispensingId))
                .andExpect(status().isOk()).andReturn());

        // 同键同参重放
        mvc.perform(post("/api/dispensings/{id}/changes", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-1\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"2026-09-26T01:05:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键异参 → 409
        mvc.perform(post("/api/dispensings/{id}/changes", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-1\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"2026-09-26T01:06:00Z\"}"))
                .andExpect(status().isConflict());
        // 期望版本不匹配 → 409
        mvc.perform(post("/api/dispensings/{id}/changes", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-STALE\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":9,\"newAdministrationTime\":\"2026-09-26T01:05:00Z\"}"))
                .andExpect(status().isConflict());
        // 新时刻不晚于母液参考时刻 → 400
        mvc.perform(post("/api/dispensings/{id}/changes", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-BADTIME\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"2026-09-26T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 时刻格式非法 → 400；缺 requestId → 400
        mvc.perform(post("/api/dispensings/{id}/changes", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-BADFMT\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"not-a-time\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/dispensings/{id}/changes", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"2026-09-26T01:05:00Z\"}"))
                .andExpect(status().isBadRequest());

        // 双人复核：同一人第二签 → 409
        mvc.perform(post("/api/changes/{cid}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-REV-1\",\"checker\":\"carol\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("FIRST_REVIEWED"));
        mvc.perform(post("/api/changes/{cid}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-REV-1\",\"checker\":\"carol\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post("/api/changes/{cid}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-REV-SAME\",\"checker\":\"carol\"}"))
                .andExpect(status().isConflict());
        // 第二签生效
        mvc.perform(post("/api/changes/{cid}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-REV-2\",\"checker\":\"dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("APPROVED"))
                .andExpect(jsonPath("$.change.status").value("APPROVED"))
                .andExpect(jsonPath("$.dispensing.version").value(2))
                .andExpect(jsonPath("$.dispensing.firstChecker").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.dispensing.status").value("CALCULATED"));

        // 旧签认失效：未重新核对新版本发药 → 422
        mvc.perform(post("/api/dispensings/dispense").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-DISP-NEW\",\"dispensingId\":" + dispensingId
                                + ",\"remainingActivityBq\":0.0,\"measuredAt\":\"2026-09-26T01:05:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 版本证据：两版本，v1 SUPERSEDED / v2 ACTIVE
        mvc.perform(get("/api/dispensings/{id}/versions", dispensingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].version").value(1))
                .andExpect(jsonPath("$.versions[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.versions[1].status").value("ACTIVE"));
        // 候选证据含双人复核链
        mvc.perform(get("/api/dispensings/{id}/changes", dispensingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].reviewChain.length()").value(2));

        // 不存在候选 → 404
        mvc.perform(post("/api/changes/{cid}/reviews", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-REV-X\",\"checker\":\"carol\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void dispenseFirstRejectsPendingChangeOverHttp() throws Exception {
        long dispensingId = newCheckedDispensing();
        MvcResult submit = mvc.perform(post("/api/dispensings/{id}/changes", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-P\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"2026-09-26T01:05:00Z\"}"))
                .andExpect(status().isOk()).andReturn();
        long changeId = extractChangeId(submit);

        // 候选待决期间原版本合法发药
        mvc.perform(post("/api/dispensings/dispense").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-DISP-P\",\"dispensingId\":" + dispensingId
                                + ",\"remainingActivityBq\":0.0,\"measuredAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPENSED"));
        // 第二签：发药先成，候选被拒，历史不重开
        mvc.perform(post("/api/changes/{cid}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-REV-P\",\"checker\":\"dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("REJECTED"))
                .andExpect(jsonPath("$.change.status").value("REJECTED"))
                .andExpect(jsonPath("$.dispensing.version").value(1))
                .andExpect(jsonPath("$.dispensing.status").value("DISPENSED"));
        // 已发药再提交变更 → 422
        mvc.perform(post("/api/dispensings/{id}/changes", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-LATE\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"2026-09-26T01:05:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private long newCheckedDispensing() throws Exception {
        long calibratorId = postAndGetId("/api/calibrators",
                "{\"code\":\"CAL-CHG-" + System.nanoTime() + "\",\"name\":\"合成活度计\"}");
        mvc.perform(post("/api/calibrators/daily-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHK-CHG-" + System.nanoTime() + "\",\"calibratorId\":"
                                + calibratorId + ",\"checkDate\":\"2026-09-26\",\"referenceActivityBq\":1000.0,"
                                + "\"measuredActivityBq\":1000.0}"))
                .andExpect(status().isOk());
        long motherId = postAndGetId("/api/mother-liquors",
                "{\"batchCode\":\"ML-CHG-" + System.nanoTime() + "\",\"nuclideCode\":\"SYN-1H\","
                        + "\"initialActivityBq\":2200.0,\"referenceTime\":\"2026-09-26T00:00:00Z\"}");
        long prescriptionId = postAndGetId("/api/prescriptions",
                "{\"code\":\"RX-CHG-" + System.nanoTime() + "\",\"nuclideCode\":\"SYN-1H\","
                        + "\"prescribedActivityBq\":1000.0,\"administrationTime\":\"2026-09-26T01:00:00Z\"}");
        long dispensingId = postAndGetId("/api/dispensings/calculate",
                "{\"requestId\":\"REQ-CALC-CHG-" + System.nanoTime() + "\",\"motherLiquorId\":" + motherId
                        + ",\"prescriptionId\":" + prescriptionId + ",\"calibratorId\":" + calibratorId + "}");
        mvc.perform(post("/api/dispensings/{id}/checks", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"alice\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/dispensings/{id}/checks", dispensingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"bob\"}"))
                .andExpect(status().isOk());
        return dispensingId;
    }

    private long postAndGetId(String url, String body) throws Exception {
        MvcResult result = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        String json = result.getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private long extractChangeId(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"change(?:s\":\\[)?\":?\\{\"id\":(\\d+)").matcher(json);
        if (!matcher.find()) {
            matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
            assertThat(matcher.find()).as("change id present in: " + json).isTrue();
        }
        return Long.parseLong(matcher.group(1));
    }
}
