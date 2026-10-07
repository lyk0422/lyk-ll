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
 * 跨罐调拨 HTTP 边界：200 主流程、400 参数非法、404 对象不存在、
 * 409 版本冲突/同键异参、422 业务拒绝、同键同参重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TankTransferApiTest {

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

    private void registerOk(String station, String tankCode, String requestId, String variety,
                            String capacity, String safety, String balance) throws Exception {
        mockMvc.perform(post(BASE + "/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stationCode": "%s",
                                  "tankCode": "%s",
                                  "requestId": "%s",
                                  "capacityKg": %s,
                                  "safetyReserveKg": %s,
                                  "initialBalanceKg": %s,
                                  "registeredAt": "2026-09-26T08:00:00",
                                  "varietyNo": "%s"
                                }
                                """.formatted(station, tankCode, requestId, capacity, safety,
                                balance, variety)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.varietyNo").value(variety))
                .andExpect(jsonPath("$.version").value(1));
    }

    private String applyJson(String requestId, String qty, String batch) {
        return """
                {
                  "sourceTankCode": "TK01",
                  "targetTankCode": "TK02",
                  "varietyNo": "VAR-TBT",
                  "requestId": "%s",
                  "quantityKg": %s,
                  "transportBatchNo": "%s",
                  "operators": ["OP-A", "OP-B"],
                  "appliedAt": "2026-09-26T01:00:00Z"
                }
                """.formatted(requestId, qty, batch);
    }

    private long applyOk(String requestId, String qty, String batch) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(requestId, qty, batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.transferVersion").value(1))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("transferId").asLong();
    }

    @Test
    void applyConfirmFlowOverHttpKeepsTotalConstant() throws Exception {
        registerOk("ST01", "TK01", "REG-1", "VAR-TBT", "1000", "100", "500");
        registerOk("ST02", "TK02", "REG-2", "VAR-TBT", "800", "50", "100");

        // 10.125 公斤调拨：双侧预占
        long transferId = applyOk("TR-1", "10.125", "TB-1");
        mockMvc.perform(get(BASE).param("tankCode", "TK01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedKg").value(10.125))
                .andExpect(jsonPath("$.incomingReservedKg").value(0.0));
        mockMvc.perform(get(BASE).param("tankCode", "TK02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomingReservedKg").value(10.125))
                .andExpect(jsonPath("$.availableKg").value(100.0));

        // 双人确认执行：两罐总量不变
        mockMvc.perform(post(BASE + "/transfer/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transferId": %d,
                                  "requestId": "TC-1",
                                  "expectedVersion": 1,
                                  "actualQtyKg": 10.125,
                                  "credentialNo": "CRED-1",
                                  "confirmedAt": "2026-09-26T02:00:00Z"
                                }
                                """.formatted(transferId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.sourceBalanceKg").value(489.875))
                .andExpect(jsonPath("$.targetBalanceKg").value(110.125))
                .andExpect(jsonPath("$.transferVersion").value(2));

        // 调拨查询与复算
        mockMvc.perform(get(BASE + "/transfer").param("transferId", String.valueOf(transferId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.credentialNo").value("CRED-1"));
        mockMvc.perform(get(BASE + "/transfers").param("tankCode", "TK01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(BASE + "/transfer/recompute")
                        .param("transferId", String.valueOf(transferId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferredKg").value(10.125))
                .andExpect(jsonPath("$.netBalanceDeltaKg").value(0.0))
                .andExpect(jsonPath("$.sourceBalanceKg").value(489.875))
                .andExpect(jsonPath("$.targetBalanceKg").value(110.125));
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        registerOk("ST01", "TK01", "REG-1", "VAR-TBT", "1000", "100", "500");
        registerOk("ST02", "TK02", "REG-2", "VAR-TBT", "800", "50", "100");
        // 超过三位小数：拒绝而非舍入
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("TR-1", "10.1255", "TB-1")))
                .andExpect(status().isBadRequest());
        // 缺必填字段
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceTankCode": "TK01", "targetTankCode": "TK02"}
                                """))
                .andExpect(status().isBadRequest());
        // 非正数量
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("TR-2", "0", "TB-2")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownObjectsReturn404() throws Exception {
        registerOk("ST01", "TK01", "REG-1", "VAR-TBT", "1000", "100", "500");
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("TR-1", "10", "TB-1")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/transfer/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transferId": 999,
                                  "requestId": "TC-1",
                                  "expectedVersion": 1,
                                  "actualQtyKg": 10,
                                  "credentialNo": "CRED-1",
                                  "confirmedAt": "2026-09-26T02:00:00Z"
                                }
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/transfer").param("transferId", "999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void businessRejectionsReturn422() throws Exception {
        registerOk("ST01", "TK01", "REG-1", "VAR-TBT", "2000", "100", "1500");
        registerOk("ST02", "TK02", "REG-2", "VAR-TBT", "800", "50", "100");
        // 两罐相同
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceTankCode": "TK01",
                                  "targetTankCode": "TK01",
                                  "varietyNo": "VAR-TBT",
                                  "requestId": "TR-1",
                                  "quantityKg": 10,
                                  "transportBatchNo": "TB-1",
                                  "operators": ["OP-A", "OP-B"],
                                  "appliedAt": "2026-09-26T01:00:00Z"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
        // 源可用量扣预占后低于安全储备（1500 − 1401 < 100）
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("TR-2", "1401", "TB-2")))
                .andExpect(status().isUnprocessableEntity());
        // 目标余额加接收预占超容量（100 + 701 > 800）
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("TR-3", "701", "TB-3")))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：修正后同键受理（100 + 700 = 800 恰满允许）
        applyOk("TR-3", "700", "TB-3");
        // 运输批号不能以另一个 requestId 再次入账
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("TR-4", "10", "TB-3")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void versionMismatchReturns409() throws Exception {
        registerOk("ST01", "TK01", "REG-1", "VAR-TBT", "1000", "100", "500");
        registerOk("ST02", "TK02", "REG-2", "VAR-TBT", "800", "50", "100");
        long transferId = applyOk("TR-1", "10", "TB-1");
        mockMvc.perform(post(BASE + "/transfer/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transferId": %d,
                                  "requestId": "TC-1",
                                  "expectedVersion": 5,
                                  "actualQtyKg": 10,
                                  "credentialNo": "CRED-1",
                                  "confirmedAt": "2026-09-26T02:00:00Z"
                                }
                                """.formatted(transferId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void idempotentReplayAndConflictOverHttp() throws Exception {
        registerOk("ST01", "TK01", "REG-1", "VAR-TBT", "1000", "100", "500");
        registerOk("ST02", "TK02", "REG-2", "VAR-TBT", "800", "50", "100");
        // 同键同参：重放首次冻结结果
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("TR-1", "10", "TB-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(false));
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("TR-1", "10", "TB-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));
        // 同键异参：409
        mockMvc.perform(post(BASE + "/transfer/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("TR-1", "20", "TB-1")))
                .andExpect(status().isConflict());
        // 重放未重复预占
        mockMvc.perform(get(BASE).param("tankCode", "TK01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedKg").value(10.0));
    }

    @Test
    void cancelFlowOverHttp() throws Exception {
        registerOk("ST01", "TK01", "REG-1", "VAR-TBT", "1000", "100", "500");
        registerOk("ST02", "TK02", "REG-2", "VAR-TBT", "800", "50", "100");
        long transferId = applyOk("TR-1", "10", "TB-1");
        mockMvc.perform(post(BASE + "/transfer/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transferId": %d,
                                  "requestId": "TX-1",
                                  "expectedVersion": 1,
                                  "cancelledAt": "2026-09-26T03:00:00Z"
                                }
                                """.formatted(transferId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.releasedQtyKg").value(10.0))
                .andExpect(jsonPath("$.transferVersion").value(2));
        mockMvc.perform(get(BASE).param("tankCode", "TK01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedKg").value(0.0));
        mockMvc.perform(get(BASE).param("tankCode", "TK02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomingReservedKg").value(0.0));
    }
}
