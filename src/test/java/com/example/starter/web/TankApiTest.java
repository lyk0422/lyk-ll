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
 * 储罐 HTTP 边界：200 主流程与幂等重放、400 参数非法、404 对象不存在、
 * 409 版本冲突与同键异参、422 业务拒绝、含分隔符文本的指纹无歧义区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TankApiTest {

    private static final String BASE = "/api/gate-station/tank";

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
                  "capacityKg": %s,
                  "safetyReserveKg": %s,
                  "initialBalanceKg": %s,
                  "registeredAt": "2026-09-25T10:00:00"
                }
                """.formatted(requestId, capacity, reserve, balance);
    }

    private void register(String requestId) throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(requestId, "100", "10", "50")))
                .andExpect(status().isOk());
    }

    private String refillJson(String requestId, long expectedVersion, String batchNo,
                              String handlersJson) {
        return """
                {
                  "stationCode": "ST01",
                  "requestId": "%s",
                  "expectedVersion": %d,
                  "transportBatchNo": "%s",
                  "quantityKg": 10,
                  "acceptConcentrationMgL": 100.000,
                  "handlers": %s,
                  "confirmedAt": "2026-09-25T11:00:00"
                }
                """.formatted(requestId, expectedVersion, batchNo, handlersJson);
    }

    @Test
    void registerReplayAndNumericEquivalenceOverHttp() throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("API-REG-1", "100", "10", "50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        // 数值文本等价（100 与 100.000）：同键同参重放首次冻结结果
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("API-REG-1", "100.000", "10.0", "50.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 同键异参：409
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("API-REG-1", "100", "10", "51")))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "",
                                  "capacityKg": 100,
                                  "safetyReserveKg": 10,
                                  "initialBalanceKg": 50,
                                  "registeredAt": "2026-09-25T10:00:00"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reserveEqualCapacityReturns422() throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("API-REG-2", "100", "100", "50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void unknownStationReturns404() throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "NOPE",
                                  "requestId": "API-REG-3",
                                  "capacityKg": 100,
                                  "safetyReserveKg": 10,
                                  "initialBalanceKg": 50,
                                  "registeredAt": "2026-09-25T10:00:00"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionConflictReturns409() throws Exception {
        register("API-REG-4");
        mockMvc.perform(post(BASE + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "API-ADJ-1",
                                  "expectedVersion": 9,
                                  "capacityKg": 120,
                                  "safetyReserveKg": 20,
                                  "adjustedAt": "2026-09-25T12:00:00"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void sameHandlerRefillReturns422() throws Exception {
        register("API-REG-5");
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("API-REF-1", 1L, "B-1", "[\"OP-A\", \"OP-A\"]")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void separatorInHandlerTextDistinguishesFingerprint() throws Exception {
        register("API-REG-6");
        // 交接人文本含分隔符：["A|B","C"] 与 ["A","B|C"] 结构不同，指纹必须区分
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("API-REF-2", 1L, "B-2", "[\"A|B\", \"C\"]")))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/refill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refillJson("API-REF-2", 1L, "B-2", "[\"A\", \"B|C\"]")))
                .andExpect(status().isConflict());
    }

    @Test
    void statusAndLedgerQueryOverHttp() throws Exception {
        register("API-REG-7");
        mockMvc.perform(get(BASE + "/status").param("stationCode", "ST01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceKg").value(50.000))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get(BASE + "/ledger")
                        .param("stationCode", "ST01").param("bizDate", "2026-09-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entryType").value("REGISTER"));
        // 未绑定储罐的旧门站：404
        mockMvc.perform(get(BASE + "/status").param("stationCode", "ST02"))
                .andExpect(status().isNotFound());
    }
}
