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
 * 年度检验与大修门禁 HTTP 层测试：验证 REST 入口、参数校验与错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class InspectionApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM overhaul_inspection_item");
        jdbc.update("DELETE FROM overhaul_inspection");
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
                        .content("{\"name\":\"受检海盗船\",\"maxLoad\":10,\"maxSpeed\":30,"
                                + "\"inspectionRequired\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CHECK"))
                .andExpect(jsonPath("$.inspectionRequired").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"rideId\":(\\d+).*", "$1"));
    }

    private void makeReady(long id, String keyPrefix) throws Exception {
        mvc.perform(post("/api/rides/{id}/daily-check", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + keyPrefix + "-check\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":true},"
                                + "{\"itemName\":\"安全带\",\"passed\":true},"
                                + "{\"itemName\":\"联锁装置\",\"passed\":true}]}"))
                .andExpect(status().isOk());
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/rides/{id}/trial-runs", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"" + keyPrefix + "-t" + i
                                    + "\",\"normal\":true}"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("REST 主流程：登记证书→日检→试运行→开放→资格证据查询")
    void restInspectionHappyPath() throws Exception {
        long id = createInspectedRide();
        // 登记证书（创建后版本为1），默认截止 = 合格时刻 + 365×24小时
        mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-cert\",\"expectedVersion\":1,"
                                + "\"passedAt\":\"2026-09-20T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificate.passedAt").value("2026-09-20T00:00:00Z"))
                .andExpect(jsonPath("$.certificate.validUntil").value("2027-09-20T00:00:00Z"))
                .andExpect(jsonPath("$.certificate.current").value(true))
                .andExpect(jsonPath("$.version").value(2));

        makeReady(id, "r");
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        mvc.perform(get("/api/rides/{id}/eligibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.inspectionRequired").value(true))
                .andExpect(jsonPath("$.overhaulVersion").value(0));
        mvc.perform(get("/api/rides/{id}/annual-certificates", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].current").value(true));
    }

    @Test
    @DisplayName("REST 大修流程：大修→监督检验双人确认→资格恢复")
    void restOverhaulFlow() throws Exception {
        long id = createInspectedRide();
        mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"o-cert\",\"expectedVersion\":1,"
                                + "\"passedAt\":\"2026-09-20T00:00:00Z\"}"))
                .andExpect(status().isOk());
        makeReady(id, "o");
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"o-open\"}"))
                .andExpect(status().isOk());

        // 登记大修：开放中设备暂停开放（此前已推进到版本7）
        mvc.perform(post("/api/rides/{id}/overhauls", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"o-oh\",\"expectedVersion\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overhaulVersion").value(1))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // 监督检验双人确认（版本已推进到8）
        mvc.perform(post("/api/rides/{id}/overhaul-inspections", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"o-insp\",\"expectedVersion\":8,"
                                + "\"inspectorOne\":\"检验员甲\",\"inspectorTwo\":\"检验员乙\","
                                + "\"items\":[{\"itemName\":\"主体结构\",\"passed\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allPassed").value(true))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mvc.perform(get("/api/rides/{id}/eligibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.overhaulInspectionConfirmed").value(true));
        mvc.perform(get("/api/rides/{id}/overhaul-inspections", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].overhaul_version").value(1))
                .andExpect(jsonPath("$[0].items[0].item_name").value("主体结构"));
    }

    @Test
    @DisplayName("REST 错误语义：缺参400、设备不存在404、无证书开放422、版本冲突409")
    void restErrorSemantics() throws Exception {
        long id = createInspectedRide();
        // 缺 expectedVersion → 400
        mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert\","
                                + "\"passedAt\":\"2026-09-20T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        // 设备不存在 → 404
        mvc.perform(post("/api/rides/{id}/annual-certificates", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert\",\"expectedVersion\":1,"
                                + "\"passedAt\":\"2026-09-20T00:00:00Z\"}"))
                .andExpect(status().isNotFound());
        // 无证书开放 → 422
        makeReady(id, "e");
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-open\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));
        // 版本冲突 → 409
        mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert2\",\"expectedVersion\":99,"
                                + "\"passedAt\":\"2026-09-20T00:00:00Z\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("REST 撤销：撤销当前证书后资格失效，重复撤销幂等成功")
    void restRevokeFlow() throws Exception {
        long id = createInspectedRide();
        String body = mvc.perform(post("/api/rides/{id}/annual-certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"v-cert\",\"expectedVersion\":1,"
                                + "\"passedAt\":\"2026-09-20T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long certId = Long.parseLong(
                body.replaceAll(".*\"certificateId\":(\\d+).*", "$1"));

        mvc.perform(post("/api/rides/{id}/annual-certificates/{certId}/revoke", id, certId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"v-revoke\",\"expectedVersion\":2,"
                                + "\"reason\":\"合成撤销\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificate.revoked").value(true));
        mvc.perform(get("/api/rides/{id}/eligibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));
        // 重复撤销幂等成功
        mvc.perform(post("/api/rides/{id}/annual-certificates/{certId}/revoke", id, certId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"v-revoke-2\",\"expectedVersion\":3}"))
                .andExpect(status().isOk());
        // 撤销不存在证书 → 404
        mvc.perform(post("/api/rides/{id}/annual-certificates/{certId}/revoke", id, 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"v-revoke-3\",\"expectedVersion\":3}"))
                .andExpect(status().isNotFound());
    }
}
