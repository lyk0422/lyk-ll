package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 初查处置与限功率指令：同事务落库、最严格限值、执行确认边界、结案重算。
 */
class ReviewAndDirectiveTest extends BaseIntegrationTest {

    private long executingDirectiveCount(long transmitterId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM power_directive WHERE transmitter_id = ? AND status = 'EXECUTING'",
                Integer.class, transmitterId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("初查确认干扰：事件状态与限功率指令同事务落库，限值取自合成保护表")
    void reviewConfirmedIssuesDirectives() throws Exception {
        long eventId = createEventViaComplaints("514.250", "rd");
        long alpha = transmitterIdOf("TX-ALPHA");
        long beta = transmitterIdOf("TX-BETA");

        reviewEvent(eventId, "rd-review-1", "CONFIRMED_INTERFERENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.executingDirectiveIds.length()").value(2));

        assertThat(executingDirectiveCount(alpha)).isEqualTo(1);
        assertThat(executingDirectiveCount(beta)).isEqualTo(1);
        Integer alphaLimit = jdbc.queryForObject(
                "SELECT limit_watts FROM power_directive WHERE transmitter_id = ? AND status = 'EXECUTING'",
                Integer.class, alpha);
        assertThat(alphaLimit).isEqualTo(2000);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM interference_event WHERE id = ?", String.class, eventId))
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("保护表缺限值时确认干扰失败并整体回滚，事件保持 OPEN")
    void reviewRollsBackWhenProtectionLimitMissing() throws Exception {
        long eventId = createEventViaComplaints("700.000", "rb");

        reviewEvent(eventId, "rb-review-1", "CONFIRMED_INTERFERENCE")
                .andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM interference_event WHERE id = ?", String.class, eventId))
                .isEqualTo("OPEN");
        assertThat(countRows("power_directive")).isZero();
        // 失败的初查不留下幂等记录（允许重试）；投诉登记本身的幂等记录不在此断言范围
        Integer reviewRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE action = 'EVENT_REVIEW'",
                Integer.class);
        assertThat(reviewRecords).isZero();
    }

    @Test
    @DisplayName("初查按 requestId 幂等：同键同参返回首次结果，同键异参 409")
    void reviewIdempotency() throws Exception {
        long eventId = createEventViaComplaints("514.250", "ri");

        var first = reviewEvent(eventId, "ri-review", "CONFIRMED_INTERFERENCE")
                .andExpect(status().isOk()).andReturn();
        var replay = reviewEvent(eventId, "ri-review", "CONFIRMED_INTERFERENCE")
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(countRows("power_directive")).isEqualTo(2);

        // 同键异参：409，不产生新指令
        mockMvc.perform(post("/api/events/" + eventId + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "ri-review",
                                "reviewerId", "reviewer-1",
                                "conclusion", "NOT_INTERFERENCE"))))
                .andExpect(status().isConflict());
        assertThat(countRows("power_directive")).isEqualTo(2);

        // 不同 requestId 重复初查：422
        reviewEvent(eventId, "ri-review-2", "NOT_INTERFERENCE")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("多未结事件采用最严格限值；关闭其中一件只回升到剩余最严限值，不恢复满功率")
    void strictestLimitAcrossOpenEvents() throws Exception {
        long alpha = transmitterIdOf("TX-ALPHA");
        long gamma = transmitterIdOf("TX-GAMMA");

        long event514 = createEventViaComplaints("514.250", "s1");
        reviewEvent(event514, "s1-review", "CONFIRMED_INTERFERENCE").andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT limit_watts FROM power_directive WHERE transmitter_id = ? AND status = 'EXECUTING'",
                Integer.class, alpha)).isEqualTo(2000);

        // 第二个未结事件（626.000，限值 1500 更严格）：TX-ALPHA 执行中指令被取代为 1500
        long event626 = createEventViaComplaints("626.000", "s2");
        reviewEvent(event626, "s2-review", "CONFIRMED_INTERFERENCE").andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT limit_watts FROM power_directive WHERE transmitter_id = ? AND status = 'EXECUTING'",
                Integer.class, alpha)).isEqualTo(1500);
        assertThat(executingDirectiveCount(alpha)).isEqualTo(1);
        assertThat(executingDirectiveCount(gamma)).isEqualTo(1);

        // 关闭 626 事件：TX-ALPHA 回升到剩余最严限值 2000，而非满功率 10000
        mockMvc.perform(post("/api/events/" + event626 + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("closedBy", "closer-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(jdbc.queryForObject(
                "SELECT limit_watts FROM power_directive WHERE transmitter_id = ? AND status = 'EXECUTING'",
                Integer.class, alpha)).isEqualTo(2000);
        assertThat(executingDirectiveCount(alpha)).isEqualTo(1);

        // 指令轨迹可复算：2000 SUPERSEDED -> 1500 SUPERSEDED -> 2000 EXECUTING
        var response = mockMvc.perform(get("/api/transmitters/" + alpha + "/directives"))
                .andExpect(status().isOk()).andReturn();
        var trajectory = objectMapper.readTree(response.getResponse().getContentAsString());
        assertThat(trajectory).hasSize(3);
        assertThat(trajectory.get(0).get("limitWatts").asInt()).isEqualTo(2000);
        assertThat(trajectory.get(0).get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(trajectory.get(1).get("limitWatts").asInt()).isEqualTo(1500);
        assertThat(trajectory.get(1).get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(trajectory.get(2).get("limitWatts").asInt()).isEqualTo(2000);
        assertThat(trajectory.get(2).get("status").asText()).isEqualTo("EXECUTING");
    }

    @Test
    @DisplayName("执行确认：实际功率恰等限值合法，超过限值拒绝确认")
    void confirmExecutionBoundary() throws Exception {
        long eventId = createEventViaComplaints("514.250", "cf");
        reviewEvent(eventId, "cf-review", "CONFIRMED_INTERFERENCE").andExpect(status().isOk());
        Long directiveId = jdbc.queryForObject(
                "SELECT id FROM power_directive WHERE status = 'EXECUTING' ORDER BY id LIMIT 1",
                Long.class);
        assertThat(directiveId).isNotNull();

        // 超过限值：422，未确认
        mockMvc.perform(post("/api/directives/" + directiveId + "/confirm-execution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actualPowerWatts", 2001, "confirmedBy", "op-1"))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject(
                "SELECT confirmed_at FROM power_directive WHERE id = ?",
                Object.class, directiveId)).isNull();

        // 恰等限值：合法
        mockMvc.perform(post("/api/directives/" + directiveId + "/confirm-execution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actualPowerWatts", 2000, "confirmedBy", "op-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualPowerWatts").value(2000));
    }

    @Test
    @DisplayName("结案校验：OPEN 事件不能结案，REJECTED 事件不能结案")
    void closeValidation() throws Exception {
        long openEvent = createEventViaComplaints("514.250", "cv1");
        mockMvc.perform(post("/api/events/" + openEvent + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("closedBy", "closer-1"))))
                .andExpect(status().isUnprocessableEntity());

        long rejectedEvent = createEventViaComplaints("626.000", "cv2");
        reviewEvent(rejectedEvent, "cv2-review", "NOT_INTERFERENCE").andExpect(status().isOk());
        mockMvc.perform(post("/api/events/" + rejectedEvent + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("closedBy", "closer-1"))))
                .andExpect(status().isUnprocessableEntity());
    }
}
