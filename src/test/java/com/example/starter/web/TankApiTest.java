package com.example.starter.web;

import com.example.starter.TestTableCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 储罐交接台账 HTTP 边界：200 主流程、400 参数非法、404 对象不存在、
 * 409 版本冲突/同键异参、422 业务拒绝、同键同参重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TankApiTest {

    private static final String BASE = "/api/gate-station/tank";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestTableCleaner cleaner;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    private String registerJson(String tankCode, String requestId, String capacity,
                                String safety, String balance) {
        return """
                {
                  "stationCode": "ST01",
                  "tankCode": "%s",
                  "requestId": "%s",
                  "capacityKg": %s,
                  "safetyReserveKg": %s,
                  "initialBalanceKg": %s,
                  "registeredAt": "2026-09-26T08:00:00"
                }
                """.formatted(tankCode, requestId, capacity, safety, balance);
    }

    private void registerOk(String tankCode, String requestId) throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(tankCode, requestId, "1000", "100", "500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    private String planJson(String tankCode, String requestId, long version, String qty) {
        return """
                {
                  "tankCode": "%s",
                  "requestId": "%s",
                  "expectedVersion": %d,
                  "plannedAt": "2026-09-26T09:00:00",
                  "requiredQtyKg": %s
                }
                """.formatted(tankCode, requestId, version, qty);
    }

    private String refillJson(String tankCode, String requestId, long version,
                              String batch, String qty, String concentration,
                              String first, String second) {
        return """
                {
                  "tankCode": "%s",
                  "requestId": "%s",
                  "expectedVersion": %d,
                  "transportBatchNo": "%s",
                  "quantityKg": %s,
                  "concentrationMgM3": %s,
                  "operators": ["%s", "%s"],
                  "receivedAt": "2026-09-26T10:00:00"
                }
                """.formatted(tankCode, requestId, version, batch, qty, concentration, first, second);
    }

    @Test
    void registerPlanReceiptFlowOverHttp() throws Exception {
        registerOk("TK01", "REG-1");

        // 预占 300：可用量 200 ≥ 安全储备 100，下发
        MvcResult planResult = mockMvc.perform(post(BASE + "/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson("TK01", "PL-1", 1, "300")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();
        long planId = objectMapper.readTree(planResult.getResponse().getContentAsString())
                .get("planId").asLong();

        // 回执实际 250：余额 250、预占清零
        mockMvc.perform(post(BASE + "/plan/receipt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "planId": %d,
                                  "requestId": "RC-1",
                                  "expectedVersion": 2,
                                  "actualQtyKg": 250,
                                  "executedAt": "2026-09-26T11:00:00"
                                }
                                """.formatted(planId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.balanceKg").value(250.0))
                .andExpect(jsonPath("$.reservedKg").value(0.0))
                .andExpect(jsonPath("$.version").value(3));

        // 台账查询：余额/预占/可用量
        mockMvc.perform(get(BASE).param("tankCode", "TK01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceKg").value(250.0))
                .andExpect(jsonPath("$.availableKg").value(250.0))
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(get(BASE + "/ledger").param("tankCode", "TK01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
        mockMvc.perform(get(BASE + "/plans").param("tankCode", "TK01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(BASE + "/refill-events").param("tankCode", "TK01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        // 安全储备恰等容量：违反 0≤安全储备<容量
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("TK01", "REG-1", "1000", "1000", "500")))
                .andExpect(status().isBadRequest());
        // 缺必填字段
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationCode": "ST01", "tankCode": "TK01"}
                                """))
                .andExpect(status().isBadRequest());
        // 超过三位小数
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("TK01", "REG-1", "1000", "100", "500.0001")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownObjectsReturn404() throws Exception {
        mockMvc.perform(post(BASE + "/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson("TK99", "PL-1", 1, "100")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/plan/receipt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "planId": 999,
                                  "requestId": "RC-1",
                                  "expectedVersion": 1,
                                  "actualQtyKg": 10,
                                  "executedAt": "2026-09-26T11:00:00"
                                }
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE).param("tankCode", "TK99"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST99",
                                  "tankCode": "TK01",
                                  "requestId": "REG-1",
                                  "capacityKg": 1000,
                                  "safetyReserveKg": 100,
                                  "initialBalanceKg": 500,
                                  "registeredAt": "2026-09-26T08:00:00"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionMismatchReturns409() throws Exception {
        registerOk("TK01", "REG-1");
        mockMvc.perform(post(BASE + "/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planJson("TK01", "PL-1", 5, "100")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void businessRejectionsReturn422() throws Exception {
        registerOk("TK01", "REG-1");
        // 单人交接（两名交接人同号）
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("TK01", "RF-1", 1, "B-1", "10", "20.000", "OP-A", "OP-A")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
        // 浓度越许可区间
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("TK01", "RF-2", 1, "B-2", "10", "25.000", "OP-A", "OP-B")))
                .andExpect(status().isUnprocessableEntity());
        // 加入后超容量（余额 500 + 600 > 1000）
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("TK01", "RF-3", 1, "B-3", "600", "20.000", "OP-A", "OP-B")))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：修正参数后同键受理
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("TK01", "RF-3", 1, "B-3", "500", "20.000", "OP-A", "OP-B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceKg").value(1000.0));
    }

    @Test
    void idempotentReplayAndConflictOverHttp() throws Exception {
        registerOk("TK01", "REG-1");
        // 同键同参：重放首次冻结结果
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("TK01", "RF-1", 1, "B-1", "10", "20.000", "OP-A", "OP-B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(false));
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("TK01", "RF-1", 1, "B-1", "10", "20.000", "OP-A", "OP-B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));
        // 同键异参：409
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("TK01", "RF-1", 1, "B-1", "20", "20.000", "OP-A", "OP-B")))
                .andExpect(status().isConflict());
        // 重放未重复入账：余额只加一次
        mockMvc.perform(get(BASE).param("tankCode", "TK01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceKg").value(510.0));
    }
}
