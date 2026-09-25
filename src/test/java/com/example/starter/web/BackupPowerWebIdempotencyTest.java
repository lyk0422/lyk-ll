package com.example.starter.web;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 层幂等语义：同键同参原样重放（含首次状态码与 X-Idempotent-Replay 标记），
 * 同键异参 409 且不改变调度状态。
 */
class BackupPowerWebIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private Fixtures fixtures;

    private void seedStationAndReading() throws Exception {
        fixtures.generator("GEN-1");
        fixtures.station("BS-1", "3000", "2000");
        mockMvc.perform(post("/api/backup-power/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-A1","stationCode":"BS-1",
                                 "outageStartAt":"2026-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/backup-power/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R1","stationCode":"BS-1",
                                 "readAt":"2026-01-01T00:00:00Z","remainingWh":1000}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void same_request_id_replays_first_response_and_does_not_dispatch_twice() throws Exception {
        seedStationAndReading();
        String body = """
                {"requestId":"REQ-D1","stationCode":"BS-1",
                 "outageStartAt":"2026-01-01T00:00:00Z"}
                """;

        mockMvc.perform(post("/api/backup-power/dispatches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.order.genCode").value("GEN-1"))
                .andExpect(header().doesNotExist("X-Idempotent-Replay"));

        mockMvc.perform(post("/api/backup-power/dispatches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.order.genCode").value("GEN-1"));

        assertThatOneOrder();
    }

    @Test
    void same_request_id_with_different_params_returns_409_and_keeps_state() throws Exception {
        seedStationAndReading();

        mockMvc.perform(post("/api/backup-power/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-D1","stationCode":"BS-1",
                                 "outageStartAt":"2026-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/backup-power/dispatches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-D1","stationCode":"BS-1",
                                 "outageStartAt":"2026-01-01T00:00:00Z","genCode":"GEN-1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_PARAM_MISMATCH"));

        assertThatOneOrder();
    }

    @Test
    void exactly_two_hour_reading_returns_created_false_and_is_replayable() throws Exception {
        fixtures.generator("GEN-1");
        fixtures.station("BS-2", "4000", "2000");
        mockMvc.perform(post("/api/backup-power/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-A1","stationCode":"BS-2",
                                 "outageStartAt":"2026-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/backup-power/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R1","stationCode":"BS-2",
                                 "readAt":"2026-01-01T00:00:00Z","remainingWh":4000}
                                """))
                .andExpect(status().isOk());

        String body = """
                {"requestId":"REQ-D1","stationCode":"BS-2",
                 "outageStartAt":"2026-01-01T00:00:00Z"}
                """;
        mockMvc.perform(post("/api/backup-power/dispatches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.estimatedMinutes").value(120));
        mockMvc.perform(post("/api/backup-power/dispatches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.created").value(false));
    }

    private void assertThatOneOrder() {
        org.assertj.core.api.Assertions.assertThat(count("dispatch_order")).isEqualTo(1);
    }
}
