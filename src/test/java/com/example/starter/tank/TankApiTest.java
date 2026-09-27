package com.example.starter.tank;

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
 * 储罐台账 HTTP 边界：登记200、预占拦截200(issued=false)、补液422、版本409、参数400、对象404。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TankApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestTableCleaner cleaner;

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    private static final String BASE = "/api/gate-station";

    private String registerJson(String requestId, String capacity, String safety, String initial) {
        return """
                {
                  "stationCode": "ST01",
                  "requestId": "%s",
                  "tankCode": "TK-HTTP",
                  "capacityKg": %s,
                  "safetyReserveKg": %s,
                  "initialBalanceKg": %s,
                  "registeredAt": "2026-09-26T08:00:00"
                }
                """.formatted(requestId, capacity, safety, initial);
    }

    @Test
    void registerIssueBlockedReplenishFlowOverHttp() throws Exception {
        // 登记：容量100 安全20 余额20
        mockMvc.perform(post(BASE + "/tank/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("H-REG", "100.000", "20.000", "20.000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.availableKg").value(20.000));

        // 预占 5 → 预占后可用15 < 20，issued=false 且生成待补液事件（HTTP 200 业务结果）
        mockMvc.perform(post(BASE + "/tank/injection/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "H-ISSUE",
                                  "requiredKg": 5.000,
                                  "expectedVersion": 1,
                                  "issuedAt": "2026-09-26T09:00:00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issued").value(false))
                .andExpect(jsonPath("$.pendingEventCreated").value(true));

        // 补液单人确认 → 422
        mockMvc.perform(post(BASE + "/tank/replenish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "H-RP-BAD",
                                  "transportBatchNo": "BATCH-H",
                                  "quantityKg": 5.000,
                                  "acceptedConcentrationMgM3": 20.000,
                                  "firstHandler": "H01",
                                  "secondHandler": "H01",
                                  "expectedVersion": 1,
                                  "deliveredAt": "2026-09-26T10:00:00"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        // 版本错误 → 409
        mockMvc.perform(post(BASE + "/tank/injection/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "H-ISSUE2",
                                  "requiredKg": 1.000,
                                  "expectedVersion": 7,
                                  "issuedAt": "2026-09-26T09:30:00"
                                }
                                """))
                .andExpect(status().isConflict());

        // 参数非法（负数量）→ 400
        mockMvc.perform(post(BASE + "/tank/injection/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "H-ISSUE3",
                                  "requiredKg": -1.000,
                                  "expectedVersion": 1,
                                  "issuedAt": "2026-09-26T09:40:00"
                                }
                                """))
                .andExpect(status().isBadRequest());

        // 未绑定储罐的门站查询 → 404
        mockMvc.perform(get(BASE + "/tank/status").param("stationCode", "ST02"))
                .andExpect(status().isNotFound());

        // 合法补液双人入账 → 200，关闭可满足事件，余额25
        mockMvc.perform(post(BASE + "/tank/replenish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "ST01",
                                  "requestId": "H-RP-OK",
                                  "transportBatchNo": "BATCH-H",
                                  "quantityKg": 5.000,
                                  "acceptedConcentrationMgM3": 20.000,
                                  "firstHandler": "H01",
                                  "secondHandler": "H02",
                                  "expectedVersion": 1,
                                  "deliveredAt": "2026-09-26T10:05:00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfterKg").value(25.000))
                .andExpect(jsonPath("$.closedPendingRequests[0]").value("H-ISSUE"));

        // 台账查询返回当前量值与流水
        mockMvc.perform(get(BASE + "/tank/status").param("stationCode", "ST01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceKg").value(25.000))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.ledger[0].entryType").value("REGISTER"))
                .andExpect(jsonPath("$.ledger[1].entryType").value("REPLENISH"));
    }
}
