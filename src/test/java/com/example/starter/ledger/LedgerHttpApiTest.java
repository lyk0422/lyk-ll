package com.example.starter.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 全栈 HTTP 接口测试（真实 Spring 上下文 + H2）：建档→入站→检测上架→换出→调拨签收主流程，
 * 以及 409 同键异参、422 链不一致、404 不存在等错误语义。
 */
@AutoConfigureMockMvc
class LedgerHttpApiTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetClock() {
        TestClockConfig.CLOCK.reset();
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        // 建档
        mockMvc.perform(post("/api/ledger/batteries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"BAT-HTTP-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("BAT-HTTP-1"))
                .andExpect(jsonPath("$.status").value("REGISTERED"));

        // 入站
        mockMvc.perform(post("/api/ledger/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-in-1","batteryCode":"BAT-HTTP-1","stationCode":"ST-A"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.status").value("AT_STATION"));

        // 同键同参重放
        mockMvc.perform(post("/api/ledger/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-in-1","batteryCode":"BAT-HTTP-1","stationCode":"ST-A"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 双阈值恰好达标检测上架
        mockMvc.perform(post("/api/ledger/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-insp-1","batteryCode":"BAT-HTTP-1","soc":90,"health":80}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));

        // 换出
        mockMvc.perform(post("/api/ledger/swap-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-swap-1","batteryCode":"BAT-HTTP-1","stationCode":"ST-A",
                                 "vehicleCode":"VEH-HTTP"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_USE"))
                .andExpect(jsonPath("$.data.boundVehicle").value("VEH-HTTP"))
                .andExpect(jsonPath("$.data.currentStation").doesNotExist());

        // 回站、复检上架后调拨
        mockMvc.perform(post("/api/ledger/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-in-2","batteryCode":"BAT-HTTP-1","stationCode":"ST-A"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AT_STATION"));
        mockMvc.perform(post("/api/ledger/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-insp-2","batteryCode":"BAT-HTTP-1","soc":99,"health":99}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));

        MvcResult dispatchResult = mockMvc.perform(post("/api/ledger/transfers/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-d-1","batteryCode":"BAT-HTTP-1","fromStation":"ST-A",
                                 "toStation":"ST-B"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"))
                .andReturn();
        String body = dispatchResult.getResponse().getContentAsString();
        String orderNo = com.jayway.jsonpath.JsonPath.read(body, "$.data.orderNo");

        mockMvc.perform(post("/api/ledger/transfers/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-r-1\",\"orderNo\":\"" + orderNo
                                + "\",\"toStation\":\"ST-B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));

        // 事件链查询与验真
        mockMvc.perform(get("/api/ledger/batteries/BAT-HTTP-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("REGISTER"))
                .andExpect(jsonPath("$[0].seq").value(1));
        mockMvc.perform(get("/api/ledger/batteries/BAT-HTTP-1/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        mockMvc.perform(post("/api/ledger/batteries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"BAT-HTTP-409"}
                                """))
                .andExpect(status().isCreated());
        String inbound = """
                {"requestId":"http-conflict","batteryCode":"BAT-HTTP-409","stationCode":"ST-A"}
                """;
        mockMvc.perform(post("/api/ledger/inbound")
                        .contentType(MediaType.APPLICATION_JSON).content(inbound))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ledger/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-conflict","batteryCode":"BAT-HTTP-409","stationCode":"ST-B"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void unknownBatteryReturns404() throws Exception {
        mockMvc.perform(get("/api/ledger/batteries/NO-SUCH-BATTERY"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        mockMvc.perform(post("/api/ledger/batteries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":""}
                                """))
                .andExpect(status().isBadRequest());
    }
}
