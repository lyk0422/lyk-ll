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
 * 检修挂牌 HTTP 层测试：REST 入口、参数校验与错误语义（400/404/409/422）。
 * 未撤锁拒开、不同人签认与结束复开门禁均经真实入口验证。
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
        jdbc.update("DELETE FROM maintenance_lock");
        jdbc.update("DELETE FROM maintenance_person");
        jdbc.update("DELETE FROM maintenance_order");
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

    private long createRide() throws Exception {
        String body = mvc.perform(post("/api/rides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"检修测试飞椅\",\"maxLoad\":10,\"maxSpeed\":40}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"rideId\":(\\d+).*", "$1"));
    }

    private long createOrder(long rideId, String requestId, String personsJson) throws Exception {
        String body = mvc.perform(post("/api/rides/{id}/maintenance-orders", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"persons\":"
                                + personsJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"orderId\":(\\d+).*", "$1"));
    }

    @Test
    @DisplayName("REST 主流程：建单→启动停运→双人签认撤锁→未撤锁拒开→结束→原复检复开放行")
    void restMaintenanceHappyPathAndOriginalReopenRequired() throws Exception {
        long rideId = createRide();
        // 走到 READY 再检修：日检 + 3 次试运行
        mvc.perform(post("/api/rides/{id}/daily-check", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-check\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":true},"
                                + "{\"itemName\":\"安全带\",\"passed\":true},"
                                + "{\"itemName\":\"联锁装置\",\"passed\":true}]}"))
                .andExpect(status().isOk());
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/rides/{id}/trial-runs", rideId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"a-t" + i + "\",\"normal\":true}"))
                    .andExpect(status().isOk());
        }

        long orderId = createOrder(rideId, "a-create", "[\"钳工甲\",\"电工乙\"]");

        // 启动：expectedVersion=1，整机停运，每人一把未撤锁
        mvc.perform(post("/api/maintenance-orders/{id}/start", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-start\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.priorStatus").value("READY"))
                .andExpect(jsonPath("$.unremovedPersons[0]").value("钳工甲"))
                .andExpect(jsonPath("$.unremovedPersons[1]").value("电工乙"));
        mvc.perform(get("/api/rides/{id}", rideId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));

        // 原开放入口必须检查未结束检修与未撤锁，无法绕行
        mvc.perform(post("/api/rides/{id}/open", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-open-blocked\"}"))
                .andExpect(status().isConflict());

        // 单人/同人签认不生效
        mvc.perform(post("/api/maintenance-orders/{id}/locks/remove", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-rm-same\",\"person\":\"钳工甲\","
                                + "\"lockHolder\":\"钳工甲\",\"guardian\":\"钳工甲\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 非本人签认不生效
        mvc.perform(post("/api/maintenance-orders/{id}/locks/remove", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-rm-notself\",\"person\":\"钳工甲\","
                                + "\"lockHolder\":\"电工乙\",\"guardian\":\"监护员丙\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 两人分别由本人与不同监护员签认撤锁
        mvc.perform(post("/api/maintenance-orders/{id}/locks/remove", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-rm-1\",\"person\":\"钳工甲\","
                                + "\"lockHolder\":\"钳工甲\",\"guardian\":\"监护员丙\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unremovedPersons.length()").value(1))
                .andExpect(jsonPath("$.unremovedPersons[0]").value("电工乙"));
        // 尚余未撤锁时结束 → 422
        mvc.perform(post("/api/maintenance-orders/{id}/finish", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-fin-early\",\"expectedVersion\":2}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/maintenance-orders/{id}/locks/remove", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-rm-2\",\"person\":\"电工乙\","
                                + "\"lockHolder\":\"电工乙\",\"guardian\":\"监护员丙\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unremovedPersons.length()").value(0));

        // 结束：版本2→3，仅清除阻断，READY 进入检修的设备回 SUSPENDED，不自动开放
        mvc.perform(post("/api/maintenance-orders/{id}/finish", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-fin\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(get("/api/rides/{id}", rideId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.checkDate").isNotEmpty())
                .andExpect(jsonPath("$.reopenCount").value(0));

        // 两人检修后仍须原资格复开：直接复开409，复检后复开成功
        mvc.perform(post("/api/rides/{id}/reopen", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-reopen-no\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/rides/{id}/recheck", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-recheck\",\"items\":["
                                + "{\"itemName\":\"压杠\",\"passed\":true}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rides/{id}/reopen", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a-reopen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reopenCount").value(1));

        // 历史清单与双人签认可查
        mvc.perform(get("/api/maintenance-orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persons.length()").value(2))
                .andExpect(jsonPath("$.locks[0].personName").value("钳工甲"))
                .andExpect(jsonPath("$.locks[0].removed").value(true))
                .andExpect(jsonPath("$.locks[0].guardian").value("监护员丙"));
        mvc.perform(get("/api/rides/{id}/maintenance-orders", rideId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("REST 错误语义：缺参400、检修单不存在404、旧版本409、已载客/占用409、异参409")
    void restMaintenanceErrorSemantics() throws Exception {
        long rideId = createRide();
        // 挂牌清单为空 → 400
        mvc.perform(post("/api/rides/{id}/maintenance-orders", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-empty\",\"persons\":[]}"))
                .andExpect(status().isBadRequest());
        // 缺 requestId → 400
        mvc.perform(post("/api/rides/{id}/maintenance-orders", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"persons\":[\"钳工甲\"]}"))
                .andExpect(status().isBadRequest());
        // 清单重复人员 → 422
        mvc.perform(post("/api/rides/{id}/maintenance-orders", rideId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-dup\",\"persons\":[\"钳工甲\",\"钳工甲\"]}"))
                .andExpect(status().isUnprocessableEntity());
        // 设备不存在建单 → 404
        mvc.perform(post("/api/rides/{id}/maintenance-orders", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-404\",\"persons\":[\"钳工甲\"]}"))
                .andExpect(status().isNotFound());

        long orderId = createOrder(rideId, "e-create", "[\"钳工甲\"]");
        // 检修单不存在 → 404
        mvc.perform(post("/api/maintenance-orders/{id}/start", 999998)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-start-404\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/maintenance-orders/{id}", 999997))
                .andExpect(status().isNotFound());
        // 启动缺 expectedVersion → 400
        mvc.perform(post("/api/maintenance-orders/{id}/start", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-start-nover\"}"))
                .andExpect(status().isBadRequest());
        // 旧版本启动 → 409
        mvc.perform(post("/api/maintenance-orders/{id}/start", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-start-stale\",\"expectedVersion\":7}"))
                .andExpect(status().isConflict());
        // 正常启动
        mvc.perform(post("/api/maintenance-orders/{id}/start", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-start\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        // 第二张进行中检修单 → 409 占用
        long otherOrder = createOrder(rideId, "e-create-2", "[\"电工乙\"]");
        mvc.perform(post("/api/maintenance-orders/{id}/start", otherOrder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-start-2\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
        // 撤锁同键异参（换监护员）→ 409
        mvc.perform(post("/api/maintenance-orders/{id}/locks/remove", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-rm\",\"person\":\"钳工甲\","
                                + "\"lockHolder\":\"钳工甲\",\"guardian\":\"监护员丙\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/maintenance-orders/{id}/locks/remove", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-rm\",\"person\":\"钳工甲\","
                                + "\"lockHolder\":\"钳工甲\",\"guardian\":\"监护员丁\"}"))
                .andExpect(status().isConflict());
        // 重复撤锁（新键）→ 409
        mvc.perform(post("/api/maintenance-orders/{id}/locks/remove", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-rm-again\",\"person\":\"钳工甲\","
                                + "\"lockHolder\":\"钳工甲\",\"guardian\":\"监护员丁\"}"))
                .andExpect(status().isConflict());
    }
}
