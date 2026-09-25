package com.example.starter.water;

import java.time.Instant;

import com.example.starter.water.testsupport.MutableTimeProvider;
import com.example.starter.water.testsupport.TestClockConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 经真实 REST 入口验证主流程与失败分支的 HTTP 语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class WaterRestApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableTimeProvider clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : new String[] {"meter_reading", "declaration_idempotency", "plan_version",
                "annual_quota", "restriction_event", "meter_point", "water_user"}) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock.set(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void fullFlowThroughHttpEndpoints() throws Exception {
        // 创建取水户：年许可 100 m³。
        String userResponse = mockMvc.perform(post("/api/water/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"合成户A\",\"riverSection\":\"SEC-A\",\"annualQuota\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.restricted").value(false))
                .andReturn().getResponse().getContentAsString();
        long userId = ((Number) com.jayway.jsonpath.JsonPath.read(userResponse, "$.id")).longValue();

        // 参数校验失败 → 400。
        mockMvc.perform(post("/api/water/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"riverSection\":\"SEC-A\",\"annualQuota\":0}"))
                .andExpect(status().isBadRequest());

        // 创建计量点。
        String meterResponse = mockMvc.perform(post("/api/water/users/" + userId + "/meters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"主表\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long meterId = ((Number) com.jayway.jsonpath.JsonPath.read(meterResponse, "$.id")).longValue();

        // 申报 60 → 受理。
        mockMvc.perform(post("/api/water/declarations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"userId\":" + userId
                                + ",\"month\":\"2026-01\",\"volume\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.acceptedVolume").value(60.000))
                .andExpect(jsonPath("$.version").value(1));

        // 同键同参重放 → 200 同一版本。
        mockMvc.perform(post("/api/water/declarations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"userId\":" + userId
                                + ",\"month\":\"2026-01\",\"volume\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 同键异参 → 409。
        mockMvc.perform(post("/api/water/declarations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"userId\":" + userId
                                + ",\"month\":\"2026-01\",\"volume\":61}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 再申报 41（2 月）→ 超出剩余 40 → 整月拒绝（200 + REJECTED，可按 requestId 幂等回放）。
        mockMvc.perform(post("/api/water/declarations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-2\",\"userId\":" + userId
                                + ",\"month\":\"2026-02\",\"volume\":41}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.version").doesNotExist());

        // 限取触发。
        mockMvc.perform(post("/api/water/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riverSection\":\"SEC-A\",\"reason\":\"生态流量预警\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.affectedUsers").value(1));

        // 限取后申报 40 → 减半受理 20。
        mockMvc.perform(post("/api/water/declarations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-3\",\"userId\":" + userId
                                + ",\"month\":\"2026-03\",\"volume\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.restricted").value(true))
                .andExpect(jsonPath("$.acceptedVolume").value(20.000));

        // 读数上报与对冲。
        mockMvc.perform(post("/api/water/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meterPointId\":" + meterId
                                + ",\"readingTime\":\"2026-03-20T00:00:00Z\",\"cumulative\":130}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-03"))
                .andExpect(jsonPath("$.duplicate").value(false));
        // 重复上报 → duplicate=true 不累计。
        mockMvc.perform(post("/api/water/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meterPointId\":" + meterId
                                + ",\"readingTime\":\"2026-03-20T00:00:00Z\",\"cumulative\":130}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        // 台账：已确认受理 60+20=80，实际 130，超许可 30。
        mockMvc.perform(get("/api/water/users/" + userId + "/ledger").param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredTotal").value(80.000))
                .andExpect(jsonPath("$.actualTotal").value(130.000))
                .andExpect(jsonPath("$.sealed").value(false));

        // 封存 → 超额 30 结转次年。
        mockMvc.perform(post("/api/water/users/" + userId + "/seal").param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excess").value(30.000))
                .andExpect(jsonPath("$.carryoverToNextYear").value(30.000))
                .andExpect(jsonPath("$.sealed").value(true));
        // 重复封存幂等。
        mockMvc.perform(post("/api/water/users/" + userId + "/seal").param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excess").value(30.000))
                .andExpect(jsonPath("$.sealed").value(true));

        // 次年额度 100-30=70。
        mockMvc.perform(get("/api/water/users/" + userId + "/ledger").param("year", "2027"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveQuota").value(70.000))
                .andExpect(jsonPath("$.carryoverDeduction").value(30.000));

        // 不存在资源 → 404。
        mockMvc.perform(post("/api/water/declarations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-x\",\"userId\":999999,\"month\":\"2026-01\",\"volume\":1}"))
                .andExpect(status().isNotFound());
    }
}
