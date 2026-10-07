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
 * 检修挂牌流程 HTTP 层测试：REST 入口、参数校验与错误语义（400/404/409/422），
 * 以及两人检修后仍须原资格复开的合成主流程示例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class MaintenanceApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM maintenance_lock_removal");
        jdbc.update("DELETE FROM maintenance_lock");
        jdbc.update("DELETE FROM maintenance_person");
        jdbc.update("DELETE FROM maintenance_order");
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

    private long createRide() throws Exception {
        String body = mvc.perform(post("/api/rides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"过山车\",\"maxLoad\":10,\"maxSpeed\":80}"))
                .andExpect(status().isCreated())
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

    private long createOrder(long rideId, String requestId, String personnelJson)
            throws Exception {
        String body = mvc.perform(post("/api/rides/{id}/maintenances", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"personnel\":"
                                + personnelJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"maintenanceId\":(\\d+).*", "$1"));
    }

    @Test
    @DisplayName("REST 主流程：两人检修挂牌→双人签认撤锁→结束→仍须原资格复开")
    void restMaintenanceHappyPath() throws Exception {
        long id = createRide();
        makeReady(id, "a");
        // 两人检修单建档
        long mid = createOrder(id, "a-mc", "[\"张三\",\"李四\"]");
        // 未结束检修阻断开放
        mvc.perform(post("/api/rides/{id}/open", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-open-0\"}"))
                .andExpect(status().isConflict());
        // 启动：整机停运，两人各一把未撤锁
        mvc.perform(post("/api/rides/{id}/maintenances/{mid}/start", id, mid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-ms\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.unremovedPersonnel[0]").value("张三"))
                .andExpect(jsonPath("$.unremovedPersonnel[1]").value("李四"));
        mvc.perform(get("/api/rides/{id}", id))
                .andExpect(jsonPath("$.status").value("STOPPED"));
        // 张三的锁：本人张三+监护员李四共同确认
        mvc.perform(post("/api/rides/{id}/maintenances/{mid}/lock-removals", id, mid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-mu1\",\"personName\":\"张三\","
                                + "\"supervisorName\":\"李四\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.unremovedPersonnel[0]").value("李四"));
        // 李四的锁：本人李四+监护员张三
        mvc.perform(post("/api/rides/{id}/maintenances/{mid}/lock-removals", id, mid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-mu2\",\"personName\":\"李四\","
                                + "\"supervisorName\":\"张三\",\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unremovedPersonnel").isEmpty());
        // 全部撤锁后结束：只清除挂牌阻断，不自动开放
        mvc.perform(post("/api/rides/{id}/maintenances/{mid}/finish", id, mid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-mf\",\"expectedVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.version").value(5));
        mvc.perform(get("/api/rides/{id}", id))
                .andExpect(jsonPath("$.status").value("STOPPED"));
        // 仍须原资格流程：复检合格→复开
        mvc.perform(post("/api/rides/{id}/recheck", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-r1\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":true}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rides/{id}/reopen", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-re1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reopenCount").value(1));
        // 历史清单与双人签认可查
        mvc.perform(get("/api/rides/{id}/maintenances/{mid}", id, mid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personnel[0]").value("张三"))
                .andExpect(jsonPath("$.locks[0].confirmedBy").value("张三"))
                .andExpect(jsonPath("$.locks[0].supervisedBy").value("李四"));
    }

    @Test
    @DisplayName("REST 错误语义：缺参400、超20人400、对象不存在404、同人签认422、版本冲突409")
    void restErrorSemantics() throws Exception {
        long id = createRide();
        // 缺 requestId → 400
        mvc.perform(post("/api/rides/{id}/maintenances", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personnel\":[\"张三\"]}"))
                .andExpect(status().isBadRequest());
        // 21人超过上限 → 400
        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 1; i <= 21; i++) {
            tooMany.append(i == 1 ? "" : ",").append("\"人员").append(i).append("\"");
        }
        tooMany.append("]");
        mvc.perform(post("/api/rides/{id}/maintenances", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-mc\",\"personnel\":" + tooMany + "}"))
                .andExpect(status().isBadRequest());
        // 设备不存在 → 404
        mvc.perform(post("/api/rides/{id}/maintenances", 99999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-mc2\",\"personnel\":[\"张三\"]}"))
                .andExpect(status().isNotFound());

        long mid = createOrder(id, "e-mc3", "[\"张三\",\"李四\"]");
        // 缺 expectedVersion → 400
        mvc.perform(post("/api/rides/{id}/maintenances/{mid}/start", id, mid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-ms0\"}"))
                .andExpect(status().isBadRequest());
        // 版本冲突 → 409
        mvc.perform(post("/api/rides/{id}/maintenances/{mid}/start", id, mid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-ms1\",\"expectedVersion\":7}"))
                .andExpect(status().isConflict());
        // 正常启动
        mvc.perform(post("/api/rides/{id}/maintenances/{mid}/start", id, mid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-ms2\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        // 本人与监护员同一人 → 422
        mvc.perform(post("/api/rides/{id}/maintenances/{mid}/lock-removals", id, mid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-mu1\",\"personName\":\"张三\","
                                + "\"supervisorName\":\"张三\",\"expectedVersion\":2}"))
                .andExpect(status().isUnprocessableEntity());
        // 检修单不存在 → 404
        mvc.perform(get("/api/rides/{id}/maintenances/{mid}", id, 99999L))
                .andExpect(status().isNotFound());
    }
}
