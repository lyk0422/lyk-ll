package com.example.starter.legality.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合规服务 HTTP 边界测试：真实 Spring MVC 栈 + 嵌入式 H2，覆盖成功、409 幂等冲突、404 与 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrewLegalityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM delay_event");
        jdbcTemplate.update("DELETE FROM batch_member");
        jdbcTemplate.update("DELETE FROM check_batch");
        jdbcTemplate.update("DELETE FROM duty_record");
    }

    @Test
    void fullHttpFlowReportCheckDelayFinalize() throws Exception {
        // 1. 报到：早班档恰 13 小时，合法。
        mockMvc.perform(post("/api/crew-legality/duties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"W-1","crewId":"WC","flightNo":"WA100",
                                 "reportTime":"2026-03-10T06:00:00Z",
                                 "scheduledEndTime":"2026-03-10T19:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legal").value(true))
                .andExpect(jsonPath("$.replayed").value(false));

        // 2. 同键同参重放，返回首次判定。
        mockMvc.perform(post("/api/crew-legality/duties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"W-1","crewId":"WC","flightNo":"WA100",
                                 "reportTime":"2026-03-10T06:00:00Z",
                                 "scheduledEndTime":"2026-03-10T19:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 3. 同键异参 → 409。
        mockMvc.perform(post("/api/crew-legality/duties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"W-1","crewId":"WC","flightNo":"WA100",
                                 "reportTime":"2026-03-10T07:00:00Z",
                                 "scheduledEndTime":"2026-03-10T19:00:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_CONFLICT"));

        // 4. 整组校验：一名合法一名 FDP 违规，整组违规并列明原因。
        mockMvc.perform(post("/api/crew-legality/batches/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"WB1","members":[
                                  {"requestId":"W-1","crewId":"WC","flightNo":"WA100",
                                   "reportTime":"2026-03-10T06:00:00Z",
                                   "scheduledEndTime":"2026-03-10T19:00:00Z"},
                                  {"requestId":"W-2","crewId":"WD","flightNo":"WA101",
                                   "reportTime":"2026-03-10T22:00:00Z",
                                   "scheduledEndTime":"2026-03-11T08:01:00Z"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legal").value(false))
                .andExpect(jsonPath("$.status").value("CHECKED"))
                .andExpect(jsonPath("$.memberResults[0].legal").value(true))
                .andExpect(jsonPath("$.memberResults[1].legal").value(false))
                .andExpect(jsonPath("$.memberResults[1].reasons[0]").value("M_FDP_LIMIT"));

        // 5. 封板。
        mockMvc.perform(post("/api/crew-legality/batches/WB1/finalize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legal").value(false))
                .andExpect(jsonPath("$.replayed").value(false));
        // 重复封板幂等成功。
        mockMvc.perform(post("/api/crew-legality/batches/WB1/finalize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 6. 封板后补交延误仅登记迟到，不推翻结论。
        mockMvc.perform(post("/api/crew-legality/delays")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"W-2","delayMinutes":300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.batchFinalized").value(true))
                .andExpect(jsonPath("$.legal").value(false));
    }

    @Test
    void unknownResourcesAndValidationProduceErrorStatus() throws Exception {
        // 未知记录延误 → 404。
        mockMvc.perform(post("/api/crew-legality/delays")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"GHOST","delayMinutes":30}
                                """))
                .andExpect(status().isNotFound());

        // 未知批次封板 → 404。
        mockMvc.perform(post("/api/crew-legality/batches/GHOST/finalize"))
                .andExpect(status().isNotFound());

        // 缺字段 → 400（Bean 校验）。
        mockMvc.perform(post("/api/crew-legality/duties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","crewId":"WC",
                                 "reportTime":"2026-03-10T06:00:00Z",
                                 "scheduledEndTime":"2026-03-10T19:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
