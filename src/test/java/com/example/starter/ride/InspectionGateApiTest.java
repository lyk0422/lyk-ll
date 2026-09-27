package com.example.starter.ride;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 年度检验/大修门禁 HTTP 层测试：REST 入口、参数校验（400/404/422）与证据查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class InspectionGateApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM supervisory_check_item");
        jdbc.update("DELETE FROM supervisory_inspection");
        jdbc.update("DELETE FROM annual_certificate");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
    }

    private long createInspectedRide() throws Exception {
        String body = mvc.perform(post("/api/rides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"过山车\",\"maxLoad\":10,\"maxSpeed\":80,"
                                + "\"inspectionRequired\":true,\"cycleVersion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inspectionRequired").value(true))
                .andExpect(jsonPath("$.cycleVersion").value(1))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"rideId\":(\\d+).*", "$1"));
    }

    private void makeReady(long id) throws Exception {
        mvc.perform(post("/api/rides/{id}/daily-check", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-check\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":true},"
                                + "{\"itemName\":\"安全带\",\"passed\":true},"
                                + "{\"itemName\":\"联锁装置\",\"passed\":true}]}"))
                .andExpect(status().isOk());
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/rides/{id}/trial-runs", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"h-t" + i + "\",\"normal\":true}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("REST 受检主流程：发证→开放→大修→监督检验→换证→复开，全程证据可查")
    void inspectedFlowOverHttp() throws Exception {
        long id = createInspectedRide();
        makeReady(id);

        // 无证书开放 → 422
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-open-blocked\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("年度证书")));

        // 发证：默认截止 = 合格时刻 + 365×24h（create=v1, 日检=v2, 3次试运行=v3/4/5）
        mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-cert1\",\"expectedVersion\":5,"
                                + "\"issuedAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validNow").value(true))
                .andExpect(jsonPath("$.expiresAt").value("2027-09-26T01:00:00Z"))
                .andExpect(jsonPath("$.overhaulVersion").value(0));

        // 发证后开放成功
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 资格证据
        mvc.perform(get("/api/rides/{id}/eligibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates.annualCertificate.valid").value(true));

        // 登记大修 → OVERHAUL（发证v6，开放v7）
        mvc.perform(post("/api/rides/{id}/overhauls", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-oh1\",\"expectedVersion\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OVERHAUL"))
                .andExpect(jsonPath("$.overhaulVersion").value(1));

        // 两名检验人相同 → 400
        mvc.perform(post("/api/rides/{id}/supervisory-inspections", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-si-bad\",\"expectedVersion\":8,"
                                + "\"inspectorA\":\"张三\",\"inspectorB\":\"张三\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":true}]}"))
                .andExpect(status().isBadRequest());

        // 两名不同检验人全清单合格 → RECHECKED，不自动开放（大修v8）
        mvc.perform(post("/api/rides/{id}/supervisory-inspections", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-si1\",\"expectedVersion\":8,"
                                + "\"inspectorA\":\"张三\",\"inspectorB\":\"李四\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":true},"
                                + "{\"itemName\":\"安全带\",\"passed\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECHECKED"))
                .andExpect(jsonPath("$.reopenCount").value(0));

        // 旧年度证书大修版本落后 → 复开422
        mvc.perform(post("/api/rides/{id}/reopen", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-re-stale\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 换证绑定大修版本1（监督检验后v9）
        mvc.perform(post("/api/rides/{id}/annual-certificates/renew", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-cert2\",\"expectedVersion\":9,"
                                + "\"issuedAt\":\"2026-09-26T02:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overhaulVersion").value(1))
                .andExpect(jsonPath("$.expiresAt").value("2027-09-26T02:00:00Z"));

        // 复开成功
        mvc.perform(post("/api/rides/{id}/reopen", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-re1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reopenCount").value(1));

        // 证书证据：历史+当前各一
        mvc.perform(get("/api/rides/{id}/annual-certificates", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificates.length()").value(2))
                .andExpect(jsonPath("$.certificates[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.certificates[1].status").value("ACTIVE"));
        // 监督检验证据
        mvc.perform(get("/api/rides/{id}/supervisory-inspections", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].items.length()").value(2));
    }

    @Test
    @DisplayName("REST 参数与状态语义：缺expectedVersion 400、版本不匹配409、设备不存在404、非法截止400")
    void restValidationAndErrors() throws Exception {
        long id = createInspectedRide();

        // 缺 expectedVersion → 400
        mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e1\",\"issuedAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isBadRequest());

        // 设备不存在 → 404
        mvc.perform(post("/api/rides/999999/annual-certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e2\",\"expectedVersion\":1,"
                                + "\"issuedAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isNotFound());

        // 非法截止（不等于默认值）→ 400
        mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e3\",\"expectedVersion\":1,"
                                + "\"issuedAt\":\"2026-09-26T01:00:00Z\","
                                + "\"expiresAt\":\"2026-09-27T01:00:00Z\"}"))
                .andExpect(status().isBadRequest());

        // 版本不匹配 → 409
        mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e4\",\"expectedVersion\":42,"
                                + "\"issuedAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isConflict());

        // 合法发证成功
        mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e5\",\"expectedVersion\":1,"
                                + "\"issuedAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("旧设备创建入口保持兼容：不传受检标志时行为不变")
    void legacyCreateStillWorks() throws Exception {
        String body = mvc.perform(post("/api/rides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"旋转木马\",\"maxLoad\":12,\"maxSpeed\":40}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inspectionRequired").value(false))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"rideId\":(\\d+).*", "$1"));
        mvc.perform(get("/api/rides/{id}/eligibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates.annualCertificate.required").value(false));
    }
}
