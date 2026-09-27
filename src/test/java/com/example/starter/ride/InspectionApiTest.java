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
 * 年度检验门禁 HTTP 层测试：REST 入口、参数校验与错误语义（400/404/422/409）。
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
        jdbc.update("DELETE FROM supervision_inspection_item");
        jdbc.update("DELETE FROM supervision_inspection");
        jdbc.update("DELETE FROM overhaul_record");
        jdbc.update("DELETE FROM current_certificate");
        jdbc.update("DELETE FROM annual_certificate");
        jdbc.update("DELETE FROM daily_check_item");
        jdbc.update("DELETE FROM daily_check");
        jdbc.update("DELETE FROM trial_run");
        jdbc.update("DELETE FROM runtime_reading");
        jdbc.update("DELETE FROM ride_event");
        jdbc.update("DELETE FROM ride");
    }

    private long createInspectionRide() throws Exception {
        String body = mvc.perform(post("/api/rides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"受检摩天轮\",\"maxLoad\":10,\"maxSpeed\":30,"
                                + "\"inspectionRequired\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inspectionRequired").value(true))
                .andExpect(jsonPath("$.overhaulVersion").value(0))
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
    @DisplayName("REST 主流程：签发证书→开放→大修→监督检验→资格证据")
    void restInspectionHappyPath() throws Exception {
        long id = createInspectionRide();
        makeReady(id, "a");
        // 无证书开放 → 422
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-open-0\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 签发默认证书
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-cert\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revoked").value(false));
        // 开放成功
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        // 登记大修：开放中暂停开放，大修版本=1
        mvc.perform(post("/api/rides/{id}/overhauls", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-oh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overhaulVersion").value(1))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        // 监督检验：两名不同检验人全清单合格
        mvc.perform(post("/api/rides/{id}/supervisions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-sup\",\"overhaulVersion\":1,"
                                + "\"inspectorOne\":\"检验员甲\",\"inspectorTwo\":\"检验员乙\","
                                + "\"items\":[{\"itemName\":\"结构焊缝\",\"passed\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allPassed").value(true));
        // 资格证据：证书有效、监督已确认
        mvc.perform(get("/api/rides/{id}/eligibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openEligible").value(true))
                .andExpect(jsonPath("$.certificateValid").value(true))
                .andExpect(jsonPath("$.supervisionConfirmed").value(true))
                .andExpect(jsonPath("$.overhaulVersion").value(1));
        // 证书与大修记录可查
        mvc.perform(get("/api/rides/{id}/certificates", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/rides/{id}/certificates/current", id))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rides/{id}/overhauls", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/rides/{id}/supervisions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("REST 错误语义：缺参400、对象不存在404、业务拒绝422、版本冲突409")
    void restInspectionErrorSemantics() throws Exception {
        long id = createInspectionRide();
        // 缺 requestId → 400
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // 缺 expectedVersion → 400
        mvc.perform(post("/api/rides/{id}/certificates/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-rev\"}"))
                .andExpect(status().isBadRequest());
        // 设备不存在 → 404
        mvc.perform(post("/api/rides/{id}/certificates", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert-404\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/rides/{id}/eligibility", 999999))
                .andExpect(status().isNotFound());
        // 无当前证书可撤销 → 404
        mvc.perform(post("/api/rides/{id}/certificates/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-rev-404\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
        // 签发后撤销：版本不符 → 409，版本匹配 → 200
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rides/{id}/certificates/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-rev-stale\",\"expectedVersion\":7}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/rides/{id}/certificates/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-rev\",\"expectedVersion\":1,"
                                + "\"reason\":\"抽检不合格\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.version").value(2));
        // 撤销证据查询
        mvc.perform(get("/api/rides/{id}/revocations", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].revokeReason").value("抽检不合格"));
        // 监督检验绑定错误大修版本 → 422
        mvc.perform(post("/api/rides/{id}/supervisions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-sup\",\"overhaulVersion\":5,"
                                + "\"inspectorOne\":\"甲\",\"inspectorTwo\":\"乙\","
                                + "\"items\":[{\"itemName\":\"焊缝\",\"passed\":true}]}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
