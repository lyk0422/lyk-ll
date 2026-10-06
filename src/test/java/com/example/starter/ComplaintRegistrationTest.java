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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 投诉登记：证据校验、业务键去重、requestId 幂等、30 分钟 3 站触发规则。
 */
class ComplaintRegistrationTest extends BaseIntegrationTest {

    private MvcResult postComplaint(String requestId, String station, String frequency,
            String sampledAt, String strength, String evidence) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("stationId", station);
        body.put("frequencyMhz", frequency);
        body.put("sampledAt", sampledAt);
        body.put("fieldStrengthDbuv", strength);
        if (evidence != null) {
            body.put("evidenceRef", evidence);
        }
        return mockMvc.perform(post("/api/complaints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    @Test
    @DisplayName("有效投诉登记成功，未达触发条件不生成事件")
    void registerValidComplaint() throws Exception {
        postComplaint("req-1", "ST-1", "514.250", "2026-01-01T10:00:00Z", "68.5", "EV-1");
        mockMvc.perform(post("/api/complaints").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "req-2",
                                "stationId", "ST-2",
                                "frequencyMhz", "514.250",
                                "sampledAt", "2026-01-01T10:05:00Z",
                                "fieldStrengthDbuv", "70.0",
                                "evidenceRef", "EV-2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.eventCreated").value(false));
        assertThat(countRows("complaint")).isEqualTo(2);
        assertThat(countRows("interference_event")).isZero();
    }

    @Test
    @DisplayName("缺少合成证据标识：422 且不进入正式处置")
    void missingEvidenceRejected() throws Exception {
        mockMvc.perform(post("/api/complaints").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "req-no-evidence",
                                "stationId", "ST-1",
                                "frequencyMhz", "514.250",
                                "sampledAt", "2026-01-01T10:00:00Z",
                                "fieldStrengthDbuv", "68.5"))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(countRows("complaint")).isZero();
        assertThat(countRows("idempotency_record")).isZero();
    }

    @Test
    @DisplayName("同监测站+频率+采样时刻只登记一次，重复提交返回首次登记")
    void sameBizKeyRegisteredOnce() throws Exception {
        MvcResult first = postComplaint("req-a", "ST-1", "514.250",
                "2026-01-01T10:00:00Z", "68.5", "EV-1");
        long complaintId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("complaintId").asLong();

        mockMvc.perform(post("/api/complaints").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "req-b",
                                "stationId", "ST-1",
                                "frequencyMhz", "514.250",
                                "sampledAt", "2026-01-01T10:00:00Z",
                                "fieldStrengthDbuv", "99.9",
                                "evidenceRef", "EV-OTHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.complaintId").value(complaintId));
        assertThat(countRows("complaint")).isEqualTo(1);
    }

    @Test
    @DisplayName("同 requestId 同参数重放：返回首次结果，不产生新数据")
    void idempotentReplaySameParams() throws Exception {
        MvcResult first = postComplaint("req-idem", "ST-1", "514.250",
                "2026-01-01T10:00:00Z", "68.5", "EV-1");
        MvcResult replay = postComplaint("req-idem", "ST-1", "514.250",
                "2026-01-01T10:00:00Z", "68.5", "EV-1");
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(countRows("complaint")).isEqualTo(1);
        assertThat(countRows("idempotency_record")).isEqualTo(1);
    }

    @Test
    @DisplayName("同 requestId 异参数：409 且不产生新投诉与新事件")
    void sameRequestIdDifferentParamsConflict() throws Exception {
        postComplaint("req-fixed", "ST-1", "514.250", "2026-01-01T10:00:00Z", "68.5", "EV-1");
        mockMvc.perform(post("/api/complaints").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "req-fixed",
                                "stationId", "ST-2",
                                "frequencyMhz", "514.250",
                                "sampledAt", "2026-01-01T10:00:00Z",
                                "fieldStrengthDbuv", "68.5",
                                "evidenceRef", "EV-1"))))
                .andExpect(status().isConflict());
        assertThat(countRows("complaint")).isEqualTo(1);
        assertThat(countRows("interference_event")).isZero();
    }

    @Test
    @DisplayName("30 分钟内恰第 3 个不同监测站投诉触发事件，第 4 个不再触发")
    void thirdDistinctStationTriggersEvent() throws Exception {
        postComplaint("req-t1", "ST-1", "514.250", "2026-01-01T10:00:00Z", "68.5", "EV-1");
        postComplaint("req-t2", "ST-2", "514.250", "2026-01-01T10:10:00Z", "69.0", "EV-2");
        assertThat(countRows("interference_event")).isZero();

        MvcResult third = postComplaint("req-t3", "ST-3", "514.250",
                "2026-01-01T10:20:00Z", "70.0", "EV-3");
        var thirdJson = objectMapper.readTree(third.getResponse().getContentAsString());
        assertThat(thirdJson.get("eventCreated").asBoolean()).isTrue();
        long eventId = thirdJson.get("eventId").asLong();
        assertThat(countRows("interference_event")).isEqualTo(1);
        String status = jdbc.queryForObject(
                "SELECT status FROM interference_event WHERE id = ?", String.class, eventId);
        assertThat(status).isEqualTo("OPEN");

        MvcResult fourth = postComplaint("req-t4", "ST-4", "514.250",
                "2026-01-01T10:25:00Z", "71.0", "EV-4");
        var fourthJson = objectMapper.readTree(fourth.getResponse().getContentAsString());
        assertThat(fourthJson.get("eventCreated").asBoolean()).isFalse();
        assertThat(countRows("interference_event")).isEqualTo(1);
    }

    @Test
    @DisplayName("窗口边界：恰满 30 分钟的投诉计入触发统计")
    void exactlyThirtyMinutesCounts() throws Exception {
        postComplaint("req-e1", "ST-1", "514.250", "2026-01-01T10:00:00Z", "68.5", "EV-1");
        postComplaint("req-e2", "ST-2", "514.250", "2026-01-01T10:30:00Z", "69.0", "EV-2");
        MvcResult third = postComplaint("req-e3", "ST-3", "514.250",
                "2026-01-01T10:30:00Z", "70.0", "EV-3");
        var json = objectMapper.readTree(third.getResponse().getContentAsString());
        assertThat(json.get("eventCreated").asBoolean()).isTrue();
        assertThat(countRows("interference_event")).isEqualTo(1);
    }

    @Test
    @DisplayName("窗口边界：超过 30 分钟的投诉不计入触发统计")
    void beyondThirtyMinutesDoesNotCount() throws Exception {
        postComplaint("req-b1", "ST-1", "514.250", "2026-01-01T10:00:00Z", "68.5", "EV-1");
        postComplaint("req-b2", "ST-2", "514.250", "2026-01-01T10:30:01Z", "69.0", "EV-2");
        MvcResult third = postComplaint("req-b3", "ST-3", "514.250",
                "2026-01-01T10:30:01Z", "70.0", "EV-3");
        var json = objectMapper.readTree(third.getResponse().getContentAsString());
        assertThat(json.get("eventCreated").asBoolean()).isFalse();
        assertThat(countRows("interference_event")).isZero();
    }
}
