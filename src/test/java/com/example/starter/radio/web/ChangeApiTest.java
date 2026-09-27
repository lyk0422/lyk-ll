package com.example.starter.radio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.service.CalibratorService;
import com.example.starter.radio.service.ChangeService;
import com.example.starter.radio.service.DispensingService;
import com.example.starter.radio.service.RegistrationService;
import com.example.starter.radio.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 给药时刻变更流程的 HTTP 层测试：候选提交、复核生效、竞争、作废与版本/变更证据查询，
 * 以及 400/404/409/422 错误语义。
 */
@AutoConfigureMockMvc
class ChangeApiTest extends IntegrationTestBase {

    private static final long ADMIN_EPOCH = T0.getEpochSecond() + 3600L;
    private static final String NEW_ADMIN = "2026-09-26T01:15:00Z";
    private static final String FAR_FUTURE = "2026-09-26T10:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private CalibratorService calibratorService;

    @Autowired
    private DispensingService dispensingService;

    @Autowired
    private ChangeService changeService;

    @Test
    void changeFlowOverHttpIncludingEvidenceQueries() throws Exception {
        long dispensingId = checkedDispensing();

        // 提交候选：200，仅形成 PENDING，候选版本号 2
        String submitBody = "{\"requestId\":\"REQ-CHG-HTTP-1\",\"dispensingId\":" + dispensingId
                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"" + NEW_ADMIN + "\"}";
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.candidateVersionNo").value(2))
                .andExpect(jsonPath("$.expectedVersion").value(1))
                .andExpect(jsonPath("$.candidateActivityBq").exists());
        long changeId = changeService.changes(dispensingId).get(0).id();

        // 候选期间有效分装仍是版本1
        mvc.perform(get("/api/dispensings/{id}", dispensingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.dispensedActivityBq").value(1100.0));

        // 同键同参重放：同一候选；同键异参 409
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(changeId));
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-CHG-HTTP-1\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"2026-09-26T01:20:00Z\"}"))
                .andExpect(status().isConflict());

        // 双人复核生效
        mvc.perform(post("/api/dispensings/changes/{id}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"carol\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstChecker").value("carol"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/dispensings/changes/{id}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"carol\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/dispensings/changes/{id}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewChain[1].checker").value("dave"));

        // 生效后版本2、旧核对有效性清除；旧版本证据保留
        mvc.perform(get("/api/dispensings/{id}", dispensingId))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.administrationTime").value(NEW_ADMIN))
                .andExpect(jsonPath("$.status").value("CALCULATED"))
                .andExpect(jsonPath("$.firstChecker").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.secondChecker").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.checkChain[0].versionNo").value(1));

        mvc.perform(get("/api/dispensings/{id}/versions", dispensingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].source").value("INITIAL"))
                .andExpect(jsonPath("$[1].source").value("CHANGE"))
                .andExpect(jsonPath("$[0].dispensedActivityBq").value(1100.0));
        mvc.perform(get("/api/dispensings/{id}/changes", dispensingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
        mvc.perform(get("/api/dispensings/changes/{id}", changeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(changeId));
    }

    @Test
    void dispenseWinsRaceOverHttpReturns422OnSecondReview() throws Exception {
        long dispensingId = checkedDispensing();
        String submitBody = "{\"requestId\":\"REQ-CHG-HTTP-2\",\"dispensingId\":" + dispensingId
                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"" + NEW_ADMIN + "\"}";
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON).content(submitBody))
                .andExpect(status().isOk());
        long changeId = changeService.changes(dispensingId).get(0).id();
        changeService.review(changeId, "carol");

        // 原版本先发药
        dispensingService.dispense("REQ-DISP-HTTP-2", dispensingId, 0.0, ADMIN_EPOCH);

        // 第二名复核竞争失败 → 422，候选证据为 REJECTED
        mvc.perform(post("/api/dispensings/changes/{id}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"dave\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/dispensings/changes/{id}", changeId))
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void changeErrorSemanticsOverHttp() throws Exception {
        long dispensingId = calculatedDispensing();

        // 参数非法 400：缺字段
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-BAD\",\"dispensingId\":" + dispensingId + "}"))
                .andExpect(status().isBadRequest());
        // 时刻格式非法 400
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-BAD2\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"not-a-time\"}"))
                .andExpect(status().isBadRequest());
        // 对象不存在 404
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-404\",\"dispensingId\":999999,\"expectedVersion\":1,"
                                + "\"newAdministrationTime\":\"" + NEW_ADMIN + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/dispensings/changes/999999")).andExpect(status().isNotFound());
        // 版本不匹配 409
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-VER\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":9,\"newAdministrationTime\":\"" + NEW_ADMIN + "\"}"))
                .andExpect(status().isConflict());
        // 超差 422，不占键
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-OUT\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"" + FAR_FUTURE + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：同键合法参数可成功
        mvc.perform(post("/api/dispensings/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-OUT\",\"dispensingId\":" + dispensingId
                                + ",\"expectedVersion\":1,\"newAdministrationTime\":\"" + NEW_ADMIN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        long changeId = changeService.changes(dispensingId).get(0).id();
        // 作废
        mvc.perform(post("/api/dispensings/changes/{id}/cancel", changeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));
        // 已作废复核 422
        mvc.perform(post("/api/dispensings/changes/{id}/reviews", changeId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checker\":\"carol\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private long calculatedDispensing() {
        long calibratorId = registrationService.registerCalibrator(unique("CAL"), "合成活度计");
        calibratorService.dailyCheck(unique("REQ"), calibratorId, CHECK_DATE, 1000.0, 1000.0);
        long motherId = registrationService.registerMotherLiquor(unique("ML"), "SYN-1H",
                2200.0, T0.getEpochSecond()).id();
        long prescriptionId = registrationService.registerPrescription(unique("RX"), "SYN-1H",
                1000.0, ADMIN_EPOCH).id();
        Dispensing d = dispensingService.calculate(unique("REQ-CALC"), motherId, prescriptionId, calibratorId);
        return d.id();
    }

    private long checkedDispensing() {
        long id = calculatedDispensing();
        dispensingService.check(id, "alice");
        dispensingService.check(id, "bob");
        return id;
    }
}
