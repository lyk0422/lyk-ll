package com.example.starter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.config.ApplicationClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 真实 HTTP 入口串联与错误语义：同键异参 409、整单拒绝 200+accepted=false、封存终态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CementApiTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationClock clock;

    @BeforeEach
    void clean() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : new String[] {
                "daily_summary", "ban_record", "late_reading_event", "idempotent_request",
                "reduction_command", "hourly_assessment", "emission_reading",
                "kiln_active_plan", "kiln_lock", "plan_fuel", "co_firing_plan"}) {
            jdbc.update("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock.reset();
    }

    private String submitJson(String requestId, String rate, String license) {
        return """
                {
                  "requestId": "%s",
                  "kilnId": "KILN-A",
                  "licenseNo": "%s",
                  "substitutionRate": %s,
                  "plannedStart": "%sT00:00:00",
                  "plannedEnd": "%sT23:59:00",
                  "fuels": [
                    {"fuelName":"RDF","fuelCategory":"RDF","highClSulfur":false,
                     "heatValueMjkg":18.5,"massKg":10000,"chlorinePct":0.01,"sulfurPct":0.05}
                  ]
                }
                """.formatted(requestId, license, rate, DAY, DAY);
    }

    @Test
    void submit_accept_then_replay_same_then_conflict_on_different_params() throws Exception {
        mockMvc.perform(post("/api/plans/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-WEB-1", "0.40", "HW-VALID-0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 同键同参：首次结果
        mockMvc.perform(post("/api/plans/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-WEB-1", "0.40", "HW-VALID-0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        // 同键异参：409
        mockMvc.perform(post("/api/plans/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-WEB-1", "0.30", "HW-VALID-0001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("IDEMPOTENT_CONFLICT"));
    }

    @Test
    void rejected_order_returns_200_with_accepted_false() throws Exception {
        mockMvc.perform(post("/api/plans/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-WEB-2", "0.41", "HW-VALID-0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejectCategory").value("RATE_EXCEEDED"));
    }

    @Test
    void validation_error_returns_400() throws Exception {
        String bad = """
                {"requestId":"","kilnId":"K1","licenseNo":"L","substitutionRate":0.4,
                 "plannedStart":"2026-09-25T00:00:00","plannedEnd":"2026-09-25T23:00:00","fuels":[]}
                """;
        mockMvc.perform(post("/api/plans/submit")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("VALIDATION_FAILED"));
    }

    @Test
    void full_flow_submit_execute_exceed_reduce_seal_over_http() throws Exception {
        // 申报
        String resp = mockMvc.perform(post("/api/plans/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("REQ-WEB-FULL", "0.40", "HW-VALID-0001")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(resp);
        String planNo = node.get("planNo").asText();

        // 执行
        mockMvc.perform(post("/api/plans/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planNo\":\"" + planNo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"));

        // 同窑第二个执行：第二单计划次日窗口（审核通过），启动时窑仍被占用 → 409
        String submit2 = """
                {
                  "requestId": "REQ-WEB-FULL-2",
                  "kilnId": "KILN-A",
                  "licenseNo": "HW-VALID-0001",
                  "substitutionRate": 0.20,
                  "plannedStart": "2026-09-26T00:00:00",
                  "plannedEnd": "2026-09-26T12:00:00",
                  "fuels": [
                    {"fuelName":"RDF","fuelCategory":"RDF","highClSulfur":false,
                     "heatValueMjkg":18.5,"massKg":10000,"chlorinePct":0.01,"sulfurPct":0.05}
                  ]
                }
                """;
        String resp2 = mockMvc.perform(post("/api/plans/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submit2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andReturn().getResponse().getContentAsString();
        String planNo2 = objectMapper.readTree(resp2).get("planNo").asText();
        mockMvc.perform(post("/api/plans/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planNo\":\"" + planNo2 + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("KILN_OCCUPIED"));

        // 超标读数
        String readings = """
                {"planNo":"%s","readings":[
                  {"measurePoint":"P1","sampleTime":"%sT08:15:00","nox":460.0,"so2":100,"dust":10},
                  {"measurePoint":"P2","sampleTime":"%sT08:45:00","nox":460.0,"so2":100,"dust":10}
                ]}
                """.formatted(planNo, DAY, DAY);
        mockMvc.perform(post("/api/emissions/readings")
                        .contentType(MediaType.APPLICATION_JSON).content(readings))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(2));

        // 下发 + 确认
        String issue = "{\"requestId\":\"REQ-WEB-ISSUE\",\"planNo\":\"" + planNo
                + "\",\"hourStart\":\"" + DAY + "T08:00:00\"}";
        mockMvc.perform(post("/api/commands/issue")
                        .contentType(MediaType.APPLICATION_JSON).content(issue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.targetRate").value(0.1000));
        String confirm = "{\"requestId\":\"REQ-WEB-CONF\",\"planNo\":\"" + planNo
                + "\",\"hourStart\":\"" + DAY + "T08:00:00\"}";
        mockMvc.perform(post("/api/commands/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 日结查询：超标1小时、实际替代率降至保底
        mockMvc.perform(get("/api/daily-summaries/" + planNo).param("date", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualSubstitutionRate").value(0.1000))
                .andExpect(jsonPath("$.exceededPeriods.length()").value(1))
                .andExpect(jsonPath("$.reductionRecords.length()").value(1))
                .andExpect(jsonPath("$.sealed").value(false));

        // 封存
        String seal = "{\"requestId\":\"REQ-WEB-SEAL\",\"planNo\":\"" + planNo
                + "\",\"summaryDate\":\"" + DAY + "\"}";
        mockMvc.perform(post("/api/daily-summaries/seal")
                        .contentType(MediaType.APPLICATION_JSON).content(seal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealed").value(true));
        mockMvc.perform(get("/api/plans/" + planNo))
                .andExpect(jsonPath("$.status").value("SEALED"));
    }
}
