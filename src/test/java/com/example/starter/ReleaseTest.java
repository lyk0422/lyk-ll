package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 解除限功率：全部事件结案 + 复测无干扰 + 运行/监测双人确认；解除与复测记录原子提交。
 */
class ReleaseTest extends BaseIntegrationTest {

    private ResultActions release(long transmitterId, String operationStaff, String monitoringStaff,
            String retestResult, String evidenceRef) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationStaffId", operationStaff);
        body.put("monitoringStaffId", monitoringStaff);
        body.put("retestResult", retestResult);
        body.put("retestSampledAt", clock.instant().toString());
        body.put("retestEvidenceRef", evidenceRef);
        return mockMvc.perform(post("/api/transmitters/" + transmitterId + "/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /**
     * 构造一台处于限功率执行中、事件已结案的发射机（TX-BETA，514.250 限值 2000）。
     */
    private long prepareClosedEventTransmitter() throws Exception {
        long eventId = createEventViaComplaints("514.250", "rel");
        reviewEvent(eventId, "rel-review", "CONFIRMED_INTERFERENCE").andExpect(status().isOk());
        mockMvc.perform(post("/api/events/" + eventId + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("closedBy", "closer-1"))))
                .andExpect(status().isOk());
        return transmitterIdOf("TX-BETA");
    }

    @Test
    @DisplayName("双人确认解除：复测记录、指令解除、确认记录原子提交")
    void releaseHappyPath() throws Exception {
        long beta = prepareClosedEventTransmitter();

        release(beta, "op-1", "mon-1", "NO_INTERFERENCE", "RETEST-EV-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directiveId").exists())
                .andExpect(jsonPath("$.retestId").exists());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM power_directive WHERE transmitter_id = ?",
                String.class, beta)).isEqualTo("RELEASED");
        assertThat(countRows("retest_record")).isEqualTo(1);
        assertThat(countRows("release_confirmation")).isEqualTo(1);
        var confirmation = jdbc.queryForMap(
                "SELECT operation_staff_id, monitoring_staff_id FROM release_confirmation");
        assertThat(confirmation.get("operation_staff_id")).isEqualTo("op-1");
        assertThat(confirmation.get("monitoring_staff_id")).isEqualTo("mon-1");
    }

    @Test
    @DisplayName("运行与监测确认人必须为两名不同人员")
    void releaseRequiresTwoDistinctStaff() throws Exception {
        long beta = prepareClosedEventTransmitter();

        release(beta, "same-person", "same-person", "NO_INTERFERENCE", "RETEST-EV-1")
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM power_directive WHERE transmitter_id = ?",
                String.class, beta)).isEqualTo("EXECUTING");
        assertThat(countRows("retest_record")).isZero();
        assertThat(countRows("release_confirmation")).isZero();
    }

    @Test
    @DisplayName("存在未结案事件时不允许解除限功率")
    void releaseBlockedWhileEventUnclosed() throws Exception {
        long eventId = createEventViaComplaints("514.250", "blk");
        reviewEvent(eventId, "blk-review", "CONFIRMED_INTERFERENCE").andExpect(status().isOk());
        long beta = transmitterIdOf("TX-BETA");

        release(beta, "op-1", "mon-1", "NO_INTERFERENCE", "RETEST-EV-1")
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM power_directive WHERE transmitter_id = ?",
                String.class, beta)).isEqualTo("EXECUTING");
        assertThat(countRows("retest_record")).isZero();
    }

    @Test
    @DisplayName("复测仍有干扰时不允许解除限功率")
    void releaseBlockedWhenRetestShowsInterference() throws Exception {
        long beta = prepareClosedEventTransmitter();

        release(beta, "op-1", "mon-1", "INTERFERENCE", "RETEST-EV-1")
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM power_directive WHERE transmitter_id = ?",
                String.class, beta)).isEqualTo("EXECUTING");
        assertThat(countRows("retest_record")).isZero();
    }

    @Test
    @DisplayName("解除事务原子性：确认记录写入失败时复测记录与指令解除整体回滚")
    void releaseAtomicRollback() throws Exception {
        long beta = prepareClosedEventTransmitter();

        // operationStaffId 超过 VARCHAR(64)，确认记录插入失败 → 整个事务回滚
        String tooLongStaffId = "x".repeat(100);
        mockMvc.perform(post("/api/transmitters/" + beta + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "operationStaffId", tooLongStaffId,
                                "monitoringStaffId", "mon-1",
                                "retestResult", "NO_INTERFERENCE",
                                "retestSampledAt", clock.instant().toString(),
                                "retestEvidenceRef", "RETEST-EV-1"))))
                .andExpect(status().is5xxServerError());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM power_directive WHERE transmitter_id = ?",
                String.class, beta)).isEqualTo("EXECUTING");
        assertThat(countRows("retest_record")).isZero();
        assertThat(countRows("release_confirmation")).isZero();
    }

    @Test
    @DisplayName("无执行中限功率指令的发射机不能解除")
    void releaseWithoutExecutingDirective() throws Exception {
        long gamma = transmitterIdOf("TX-GAMMA");
        release(gamma, "op-1", "mon-1", "NO_INTERFERENCE", "RETEST-EV-1")
                .andExpect(status().isUnprocessableEntity());
    }
}
