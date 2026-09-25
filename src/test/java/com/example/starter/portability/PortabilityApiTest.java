package com.example.starter.portability;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * 携号转网 HTTP API 测试：覆盖申请→确认→生效广播主流程、撤销、幂等重放与错误报文。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class PortabilityApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM flow_event");
        jdbc.update("DELETE FROM effective_record");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM portability_flow");
        clock.setInstant(TestClockConfiguration.T0);
    }

    private static String applyBody(String requestId, String msisdn) {
        return """
                {"requestId":"%s","msisdn":"%s","donorOperator":"OP-A","recipientOperator":"OP-B",
                 "hasUnpaidBill":false,"contractEndDate":null,"lastPortedAt":null}
                """.formatted(requestId, msisdn);
    }

    @Test
    void fullFlowApplyConfirmBroadcastAndEvents() throws Exception {
        MvcResult applyResult = mvc.perform(post("/api/portability/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("api-1", "13900000001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state", is("AUTH_ISSUED")))
                .andExpect(jsonPath("$.authCode").isString())
                .andReturn();
        long flowId = ((Number) JsonPath.read(applyResult.getResponse().getContentAsString(),
                "$.flowId")).longValue();
        String authCode = JsonPath.read(applyResult.getResponse().getContentAsString(), "$.authCode");

        mvc.perform(post("/api/portability/requests/{id}/confirm", flowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authCode\":\"" + authCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("CONFIRMED")));

        clock.setInstant(Instant.parse("2026-09-26T02:30:00Z"));
        mvc.perform(post("/api/portability/requests/{id}/broadcast", flowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-bc-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("EFFECTIVE")))
                .andExpect(jsonPath("$.broadcastRequestId", is("api-bc-1")));

        mvc.perform(get("/api/portability/requests/{id}/events", flowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].eventType", contains(
                        "FLOW_APPLIED", "AUTH_CODE_ISSUED", "DONOR_CONFIRMED", "BROADCAST_EFFECTIVE")));
    }

    @Test
    void applyReplayReturnsSameBodyAndConflictOnDifferentParams() throws Exception {
        String body = applyBody("api-2", "13900000002");
        MvcResult first = mvc.perform(post("/api/portability/requests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        mvc.perform(post("/api/portability/requests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flowId", is(((Number) JsonPath.read(
                        first.getResponse().getContentAsString(), "$.flowId")).intValue())));

        mvc.perform(post("/api/portability/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-2","msisdn":"13900000002","donorOperator":"OP-A",
                                 "recipientOperator":"OP-X","hasUnpaidBill":false}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_CONFLICT")));
    }

    @Test
    void duplicateInFlightNumberRejected() throws Exception {
        mvc.perform(post("/api/portability/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("api-3a", "13900000003")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/portability/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("api-3b", "13900000003")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("NUMBER_IN_FLIGHT")));
    }

    @Test
    void cancelFlowAndQueryState() throws Exception {
        MvcResult applyResult = mvc.perform(post("/api/portability/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("api-4", "13900000004")))
                .andExpect(status().isCreated())
                .andReturn();
        long flowId = ((Number) JsonPath.read(applyResult.getResponse().getContentAsString(),
                "$.flowId")).longValue();

        mvc.perform(post("/api/portability/requests/{id}/cancel", flowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("CANCELLED")));
        mvc.perform(get("/api/portability/requests/{id}", flowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("CANCELLED")));
    }

    @Test
    void validationFailureReturns400AndUnknownFlowReturns404() throws Exception {
        mvc.perform(post("/api/portability/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-5\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

        mvc.perform(get("/api/portability/requests/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("FLOW_NOT_FOUND")));
    }
}
