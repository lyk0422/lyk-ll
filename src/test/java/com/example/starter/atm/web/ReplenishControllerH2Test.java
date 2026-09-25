package com.example.starter.atm.web;

import com.example.starter.atm.repo.AtmRepository;
import com.example.starter.atm.support.DomainClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实 HTTP 入口 + H2 端到端语义测试（控制器、全局异常处理、JSON 序列化）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReplenishControllerH2Test {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DomainClock clock;

    @BeforeEach
    void clean() {
        clock.reset();
        jdbc.execute("DELETE FROM plan_event");
        jdbc.execute("DELETE FROM escort_checkin");
        jdbc.execute("DELETE FROM over_short");
        jdbc.execute("DELETE FROM idempotent_request");
        jdbc.execute("DELETE FROM replenish_plan");
        jdbc.execute("UPDATE cash_box SET status = 'AVAILABLE', loaded_amount = 0");
        jdbc.execute("UPDATE atm SET escort_frozen = 0");
        jdbc.execute("UPDATE guard SET frozen = 0");
        clock.setFixed(Instant.parse("2026-02-01T00:00:00Z"));
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private MvcResult call(String url, String body) throws Exception {
        return mvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    void prepare_success() throws Exception {
        String body = "{\"requestId\":\"" + rid()
                + "\",\"atmNo\":\"ATM-001\",\"boxNo\":\"BOX-001-1\",\"plannedAmount\":10000000}";
        mvc.perform(post("/api/plans/prepare").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andExpect(jsonPath("$.plannedAmount").value(10_000_000));
    }

    @Test
    void prepare_exceedingCapacityReturns422() throws Exception {
        String body = "{\"requestId\":\"" + rid()
                + "\",\"atmNo\":\"ATM-001\",\"boxNo\":\"BOX-001-1\",\"plannedAmount\":99900000000}";
        mvc.perform(post("/api/plans/prepare").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Unprocessable Entity"));
    }

    @Test
    void prepare_missingFieldReturns400() throws Exception {
        String body = "{\"requestId\":\"" + rid() + "\",\"atmNo\":\"ATM-001\"}";
        mvc.perform(post("/api/plans/prepare").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameRequestIdSameParamsReplays200AndDifferentParams409() throws Exception {
        String key = rid();
        String same = "{\"requestId\":\"" + key
                + "\",\"atmNo\":\"ATM-001\",\"boxNo\":\"BOX-001-1\",\"plannedAmount\":10000000}";
        MvcResult first = call("/api/plans/prepare", same);
        first.getResponse();
        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        String planNo = firstJson.get("planNo").asText();

        // 同键同参重放：200 + 首次单号
        mvc.perform(post("/api/plans/prepare").contentType(MediaType.APPLICATION_JSON).content(same))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planNo").value(planNo));

        // 同键异参：409
        String conflict = "{\"requestId\":\"" + key
                + "\",\"atmNo\":\"ATM-001\",\"boxNo\":\"BOX-001-1\",\"plannedAmount\":20000000}";
        mvc.perform(post("/api/plans/prepare").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownPlanReturns404() throws Exception {
        mvc.perform(get("/api/plans/PL-NO-SUCH"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fullFlowReconcileAndSealOverHttp() throws Exception {
        long amount = 12_345_678L;
        String planNo = preparePlanOverHttp(amount);
        approveOverHttp(planNo);
        checkinOverHttp(planNo, "GRD-A");
        checkinOverHttp(planNo, "GRD-B");
        countOverHttp(planNo, amount);
        deviceConfirmOverHttp(planNo, amount);
        sealOverHttp(planNo);

        // 封存后迟到设备确认：200，状态仍 SEALED，late=true，金额不被覆盖
        String late = "{\"requestId\":\"" + rid() + "\",\"deviceAmount\":77000000}";
        mvc.perform(post("/api/plans/" + planNo + "/device-confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(late))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.plannedAmount").value((int) amount))
                .andExpect(jsonPath("$.deviceAmount").value((int) amount));

        mvc.perform(get("/api/plans/" + planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    // ---------- HTTP 辅助 ----------

    private String preparePlanOverHttp(long amount) throws Exception {
        String body = "{\"requestId\":\"" + rid()
                + "\",\"atmNo\":\"ATM-001\",\"boxNo\":\"BOX-001-1\",\"plannedAmount\":" + amount + "}";
        MvcResult r = call("/api/plans/prepare", body);
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("planNo").asText();
    }

    private void approveOverHttp(String planNo) throws Exception {
        call("/api/plans/" + planNo + "/approve",
                "{\"requestId\":\"" + rid() + "\"}");
    }

    private void checkinOverHttp(String planNo, String guardNo) throws Exception {
        call("/api/plans/" + planNo + "/escort/checkins",
                "{\"requestId\":\"" + rid() + "\",\"guardNo\":\"" + guardNo + "\"}");
    }

    private void countOverHttp(String planNo, long amount) throws Exception {
        call("/api/plans/" + planNo + "/count",
                "{\"requestId\":\"" + rid()
                        + "\",\"guard1No\":\"GRD-A\",\"guard2No\":\"GRD-B\",\"countedAmount\":" + amount + "}");
    }

    private void deviceConfirmOverHttp(String planNo, long amount) throws Exception {
        call("/api/plans/" + planNo + "/device-confirm",
                "{\"requestId\":\"" + rid() + "\",\"deviceAmount\":" + amount + "}");
    }

    private void sealOverHttp(String planNo) throws Exception {
        call("/api/plans/" + planNo + "/seal",
                "{\"requestId\":\"" + rid() + "\"}");
    }
}
