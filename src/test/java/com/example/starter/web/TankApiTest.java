package com.example.starter.web;

import com.example.starter.TestTableCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 储罐与补液 HTTP 边界：200 主流程、同键同参重放、同键异参 409、
 * 参数非法 400、对象不存在 404、业务拒绝 422、版本冲突 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TankApiTest {

    private static final String BASE = "/api/gate-station";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestTableCleaner cleaner;

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    private String registerJson(String requestId, String capacity, String reserve, String balance) {
        return """
                {
                  "stationCode": "ST01",
                  "requestId": "%s",
                  "tankCode": "TK-API",
                  "capacityKg": %s,
                  "safetyReserveKg": %s,
                  "initialBalanceKg": %s,
                  "registeredAt": "2026-09-26T10:00:00"
                }
                """.formatted(requestId, capacity, reserve, balance);
    }

    private void registerStandard() throws Exception {
        mockMvc.perform(post(BASE + "/tank/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("API-TANK-1", "100", "10", "50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.idempotentReplay").value(false));
    }

    @Test
    void registerReplayAndConflictOverHttp() throws Exception {
        registerStandard();

        // 同键同参：200 重放首次冻结结果
        mockMvc.perform(post(BASE + "/tank/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("API-TANK-1", "100", "10", "50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(jsonPath("$.version").value(1));

        // 同键异参：409
        mockMvc.perform(post(BASE + "/tank/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("API-TANK-1", "200", "10", "50")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 状态查询
        mockMvc.perform(get(BASE + "/tank/status").param("stationCode", "ST01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceKg").value(50.0))
                .andExpect(jsonPath("$.availableKg").value(50.0))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void invalidRegisterParamsReturn400() throws Exception {
        // 安全储备恰等容量：违反 0≤安全储备<容量
        mockMvc.perform(post(BASE + "/tank/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("API-TANK-2", "100", "100", "50")))
                .andExpect(status().isBadRequest());

        // 缺必填字段
        mockMvc.perform(post(BASE + "/tank/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationCode\":\"ST01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void planOnUnboundStationReturns404() throws Exception {
        mockMvc.perform(post(BASE + "/injection/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST02",
                                  "requestId": "API-PLAN-404",
                                  "plannedKg": 1,
                                  "plannedAt": "2026-09-26T10:00:00",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionConflictAndBusinessRejectionOverHttp() throws Exception {
        registerStandard();

        // 版本不符：409
        mockMvc.perform(post(BASE + "/injection/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "API-PLAN-409",
                                  "plannedKg": 10,
                                  "plannedAt": "2026-09-26T10:00:00",
                                  "expectedVersion": 9
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 单人交接：422 业务拒绝
        mockMvc.perform(post(BASE + "/refill/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "API-REFILL-422",
                                  "batchNo": "BATCH-API",
                                  "quantityKg": 10,
                                  "concentrationMgM3": 20.000,
                                  "firstOperator": "OP-A",
                                  "secondOperator": "OP-A",
                                  "confirmedAt": "2026-09-26T11:00:00",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        registerStandard();

        // 计划预占 30kg
        String planBody = mockMvc.perform(post(BASE + "/injection/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "API-PLAN-1",
                                  "plannedKg": 30,
                                  "plannedAt": "2026-09-26T10:00:00",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.version").value(2))
                .andReturn().getResponse().getContentAsString();
        Number planId = com.jayway.jsonpath.JsonPath.read(planBody, "$.planId");

        // 回执实际 25kg
        mockMvc.perform(post(BASE + "/injection/receipt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "API-RCPT-1",
                                  "planId": %d,
                                  "actualKg": 25,
                                  "executedAt": "2026-09-26T11:00:00",
                                  "expectedVersion": 2
                                }
                                """.formatted(planId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.releasedKg").value(5.0))
                .andExpect(jsonPath("$.balanceKg").value(25.0))
                .andExpect(jsonPath("$.version").value(3));

        // 补液 20kg 双人交接
        mockMvc.perform(post(BASE + "/refill/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "API-REFILL-1",
                                  "batchNo": "BATCH-FLOW",
                                  "quantityKg": 20,
                                  "concentrationMgM3": 20.000,
                                  "firstOperator": "OP-A",
                                  "secondOperator": "OP-B",
                                  "confirmedAt": "2026-09-26T12:00:00",
                                  "expectedVersion": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceKg").value(45.0))
                .andExpect(jsonPath("$.version").value(4));

        // 台账守恒：REGISTER50 + RESERVE30 + CONSUME25 + RELEASE5 + REFILL20
        mockMvc.perform(get(BASE + "/tank/ledger").param("stationCode", "ST01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }
}
