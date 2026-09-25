package com.example.starter.roaming;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.roaming.testsupport.MutableClock;

/**
 * HTTP 层端到端测试：验证接口状态码、幂等回放与错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoamingApiWebTest {

    static final Instant FIXED = Instant.parse("2026-02-20T08:00:00Z");
    static final String FP_A = "FP-AAA111-CERT-SHA256-0000000000000000000000000001";
    static final String CP_B = "CP-0001";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(FIXED);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        clock.setInstant(FIXED);
        jdbc.update("DELETE FROM discrepancy");
        jdbc.update("DELETE FROM clearing_detail");
        jdbc.update("DELETE FROM clearing_batch");
        jdbc.update("DELETE FROM meter_snapshot");
        jdbc.update("DELETE FROM charging_session");
        jdbc.update("DELETE FROM idempotent_request");
    }

    @Test
    void full_flow_over_http_authorize_meter_stop_freeze_seal_writeoff() throws Exception {
        // 1. 授权
        MvcResult auth = mvc.perform(post("/api/roaming/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-auth-1","certFingerprint":"%s",
                                 "pointId":"%s","requestTime":"2026-02-20T08:00:00Z"}
                                """.formatted(FP_A, CP_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorized").value(true))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.expiresAt").value("2026-02-20T08:05:00Z"))
                .andReturn();
        String sessionNo = com.jayway.jsonpath.JsonPath.read(
                auth.getResponse().getContentAsString(), "$.sessionNo");

        // 同 requestId 同参：回放首次结果。
        mvc.perform(post("/api/roaming/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-auth-1","certFingerprint":"%s",
                                 "pointId":"%s","requestTime":"2026-02-20T08:00:00Z"}
                                """.formatted(FP_A, CP_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionNo").value(sessionNo));

        // 同 requestId 异参：409。
        mvc.perform(post("/api/roaming/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-auth-1","certFingerprint":"%s",
                                 "pointId":"CP-0002","requestTime":"2026-02-20T08:00:00Z"}
                                """.formatted(FP_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENT_PARAM_CONFLICT"));

        // 2. 计量快照（乱序：先 2 后 1）
        mvc.perform(post("/api/roaming/meters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-m-2","sessionNo":"%s","meterSeq":2,
                                 "meterWh":2000,"snapshotAt":"2026-02-20T08:02:00Z"}
                                """.formatted(sessionNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEnergyWh").value(2000));
        mvc.perform(post("/api/roaming/meters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-m-1","sessionNo":"%s","meterSeq":1,
                                 "meterWh":1000,"snapshotAt":"2026-02-20T08:01:00Z"}
                                """.formatted(sessionNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.totalEnergyWh").value(2000));

        // 3. 结束会话
        mvc.perform(post("/api/roaming/sessions/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-stop-1","sessionNo":"%s",
                                 "endTime":"2026-02-20T09:00:00Z"}
                                """.formatted(sessionNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.totalEnergyWh").value(2000));

        // 4. 冻结清分（应收 2.4 = 2kWh * 1.2，应付 1.7 = 2kWh * 0.85）
        MvcResult frozen = mvc.perform(post("/api/roaming/clearing/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-freeze-1","settlementDay":"2026-02-20"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.sessionCount").value(1))
                .andExpect(jsonPath("$.totalReceivable").value(2.4))
                .andExpect(jsonPath("$.totalPayable").value(1.7))
                .andReturn();
        String batchNo = com.jayway.jsonpath.JsonPath.read(
                frozen.getResponse().getContentAsString(), "$.batchNo");

        // 5. 封存（终态）；重复封存返回首次清分单
        mvc.perform(post("/api/roaming/clearing/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-seal-1","batchNo":"%s"}
                                """.formatted(batchNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
        mvc.perform(post("/api/roaming/clearing/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-seal-2","batchNo":"%s"}
                                """.formatted(batchNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.totalReceivable").value(2.4));

        // 6. 封存后迟到快照 → 差异挂账
        MvcResult late = mvc.perform(post("/api/roaming/meters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-m-3","sessionNo":"%s","meterSeq":3,
                                 "meterWh":2500,"snapshotAt":"2026-02-21T01:00:00Z"}
                                """.formatted(sessionNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchStatus").value("SEALED"))
                .andReturn();
        String discrepancyNo = com.jayway.jsonpath.JsonPath.read(
                late.getResponse().getContentAsString(), "$.discrepancyNo");

        // 7. 核销（留痕）；二次核销 409
        mvc.perform(post("/api/roaming/discrepancies/write-off")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-wo-1","discrepancyNo":"%s",
                                 "operator":"op-wang","remark":"人工核对后核销"}
                                """.formatted(discrepancyNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WRITTEN_OFF"))
                .andExpect(jsonPath("$.writeOffBy").value("op-wang"));
        mvc.perform(post("/api/roaming/discrepancies/write-off")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-wo-2","discrepancyNo":"%s",
                                 "operator":"op-zhao","remark":"重复核销尝试"}
                                """.formatted(discrepancyNo)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DISCREPANCY_ALREADY_WRITTEN_OFF"));
    }

    @Test
    void unknown_session_returns_404() throws Exception {
        mvc.perform(post("/api/roaming/sessions/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-stop-x","sessionNo":"CS-NOPE",
                                 "endTime":"2026-02-20T09:00:00Z"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"));
    }

    @Test
    void missing_required_field_returns_400() throws Exception {
        mvc.perform(post("/api/roaming/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"web-bad-1","certFingerprint":"%s"}
                                """.formatted(FP_A)))
                .andExpect(status().isBadRequest());
    }
}
