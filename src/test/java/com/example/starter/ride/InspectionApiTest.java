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
 * 年度检验与大修监督检验 REST 入口测试：验证 HTTP 层、参数校验与错误语义。
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
                        .content("{\"name\":\"海盗船\",\"maxLoad\":10,\"maxSpeed\":30,"
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
    @DisplayName("REST 主流程：受检设备签发证书→开放→资格证据查询")
    void restInspectionHappyPath() throws Exception {
        long id = createInspectedRide();
        makeReady(id, "h");
        // 无证书禁开放 → 422
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-open-denied\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));
        // 签发证书（当前版本5：创建1+日检1+试运行3）
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-cert\",\"expectedVersion\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.rideVersion").value(6));
        // 开放成功
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        // 证书与资格证据查询
        mvc.perform(get("/api/rides/{id}/certificates", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].valid").value(true));
        mvc.perform(get("/api/rides/{id}/eligibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatePassed").value(true))
                .andExpect(jsonPath("$.inspectionRequired").value(true));
    }

    @Test
    @DisplayName("REST 错误语义：缺参400、版本冲突409、业务拒绝422、对象不存在404")
    void restInspectionErrorSemantics() throws Exception {
        long id = createInspectedRide();
        // 缺少 requestId → 400
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        // 缺少 expectedVersion → 400
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert\"}"))
                .andExpect(status().isBadRequest());
        // 设备不存在 → 404
        mvc.perform(post("/api/rides/{id}/certificates", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/rides/{id}/eligibility", 999999))
                .andExpect(status().isNotFound());
        // expectedVersion 不匹配 → 409
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert\",\"expectedVersion\":99}"))
                .andExpect(status().isConflict());
        // 同键异参 → 409
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert\",\"expectedVersion\":1,"
                                + "\"passedAt\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isConflict());
        // 同键同参 → 重放首次冻结结果
        mvc.perform(post("/api/rides/{id}/certificates", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-cert\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateId").exists())
                .andExpect(jsonPath("$.current").value(true));
        // 大修登记
        mvc.perform(post("/api/rides/{id}/overhauls", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-ov\",\"expectedVersion\":2,"
                                + "\"note\":\"年度大修\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overhaulVersion").value(1));
        // 监督检验：两名检验人相同 → 422
        mvc.perform(post("/api/rides/{id}/supervision-inspections", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-si\",\"expectedVersion\":3,"
                                + "\"overhaulVersion\":1,"
                                + "\"inspectorOne\":\"甲\",\"inspectorTwo\":\"甲\","
                                + "\"items\":[{\"itemName\":\"制动系统\",\"passed\":true}]}"))
                .andExpect(status().isUnprocessableEntity());
        // 监督检验：两名不同检验人全清单合格 → 200
        mvc.perform(post("/api/rides/{id}/supervision-inspections", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-si2\",\"expectedVersion\":3,"
                                + "\"overhaulVersion\":1,"
                                + "\"inspectorOne\":\"甲\",\"inspectorTwo\":\"乙\","
                                + "\"items\":[{\"itemName\":\"制动系统\",\"passed\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibilityRestored").value(true));
        mvc.perform(get("/api/rides/{id}/supervision-inspections", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].items[0].item_name").value("制动系统"));
        mvc.perform(get("/api/rides/{id}/overhauls", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].overhaul_version").value(1));
    }
}
