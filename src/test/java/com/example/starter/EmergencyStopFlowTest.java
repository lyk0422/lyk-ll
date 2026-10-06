package com.example.starter;

import com.example.starter.model.ReopenRole;
import com.example.starter.service.ApiException;
import com.example.starter.web.dto.BoardingRequest;
import com.example.starter.web.dto.CabinReleaseRequest;
import com.example.starter.web.dto.ClearanceRequest;
import com.example.starter.web.dto.ReopenApplicationRequest;
import com.example.starter.web.dto.ReopenConfirmationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 紧急停车测试：救援须双人确认各段清空，单人确认不生效；复开前须完成救援。
 */
class EmergencyStopFlowTest extends BaseIntegrationTest {

    private static final Instant TRIGGER_AT = T0.plusSeconds(600);

    private long eventId;

    @BeforeEach
    void triggerEmergencyStop() {
        feedMinutes(STATION_2, T0, 9, 20.0);
        feedMinutes(STATION_3, T0, 9, 20.0);
        JsonNode resp = ingest(STATION_2, TRIGGER_AT, 20.0);
        ingest(STATION_3, TRIGGER_AT, 20.0);
        eventId = resp.get("triggeredEvent").get("eventId").asLong();
        assertThat(resp.get("triggeredEvent").get("type").asText()).isEqualTo("EMERGENCY_STOP");
        assertThat(lineStatus()).isEqualTo("EMERGENCY_STOP");
    }

    @Test
    void boardingIsForbiddenDuringEmergencyStop() {
        assertThatThrownBy(() -> controlEventService.board(LINE_ID,
                new BoardingRequest(UUID.randomUUID().toString(), 1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cabinReleaseIsNotAvailableForEmergencyStop() {
        assertThatThrownBy(() -> controlEventService.releaseCabins(eventId, SEGMENT_1,
                new CabinReleaseRequest(UUID.randomUUID().toString(), "op-1")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void singleConfirmerDoesNotClearSegment() {
        JsonNode first = confirmClearance(SEGMENT_1, "rescuer-a");
        assertThat(first.get("confirmerCount").asInt()).isEqualTo(1);
        assertThat(first.get("cleared").asBoolean()).isFalse();

        // 同一人重复确认不生效
        JsonNode again = confirmClearance(SEGMENT_1, "rescuer-a");
        assertThat(again.get("confirmerCount").asInt()).isEqualTo(1);
        assertThat(again.get("cleared").asBoolean()).isFalse();
        assertThat(countRows("segment_clearance")).isEqualTo(1);

        // 第二人确认后分段清空
        JsonNode second = confirmClearance(SEGMENT_1, "rescuer-b");
        assertThat(second.get("confirmerCount").asInt()).isEqualTo(2);
        assertThat(second.get("cleared").asBoolean()).isTrue();
    }

    @Test
    void reopenRequiresAllSegmentsClearedByTwoConfirmers() {
        confirmClearance(SEGMENT_1, "a");
        confirmClearance(SEGMENT_1, "b");
        confirmClearance(SEGMENT_2, "a");
        confirmClearance(SEGMENT_2, "b");
        // 分段 3 仅单人确认：救援未完成，复开申请拒绝
        confirmClearance(SEGMENT_3, "a");
        feedLowWind();
        assertThatThrownBy(() -> applyReopen(TRIGGER_AT.plusSeconds(16 * 60)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(eventStatus()).isEqualTo("ACTIVE");

        // 分段 3 第二人确认后救援完成，可申请复开
        confirmClearance(SEGMENT_3, "b");
        JsonNode resp = applyReopen(TRIGGER_AT.plusSeconds(16 * 60));
        assertThat(resp.get("status").asText()).isEqualTo("REOPEN_REQUESTED");

        // 双岗确认后复开归档
        confirmReopen(ReopenRole.OPERATIONS, "ops-1");
        JsonNode maint = confirmReopen(ReopenRole.MAINTENANCE, "mt-1");
        assertThat(maint.get("resumed").asBoolean()).isTrue();
        assertThat(eventStatus()).isEqualTo("RESUMED");
        assertThat(lineStatus()).isEqualTo("NORMAL");
    }

    @Test
    void clearanceOnSlowStopEventIsRejected() {
        // 清理当前紧急事件，另行触发一次缓停事件
        jdbc.update("DELETE FROM control_event");
        jdbc.update("UPDATE wind_line SET status = 'NORMAL' WHERE id = ?", LINE_ID);
        Instant t1 = T0.plusSeconds(7200);
        feedMinutes(STATION_3, t1, 10, 17.0);
        JsonNode resp = feedMinutes(STATION_4, t1, 10, 17.0);
        long slowEventId = resp.get("triggeredEvent").get("eventId").asLong();

        assertThatThrownBy(() -> controlEventService.confirmClearance(slowEventId, SEGMENT_3,
                new ClearanceRequest(UUID.randomUUID().toString(), "rescuer-a")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private JsonNode confirmClearance(long segmentId, String confirmerId) {
        ResponseEntity<String> response = controlEventService.confirmClearance(eventId, segmentId,
                new ClearanceRequest(UUID.randomUUID().toString(), confirmerId));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private JsonNode applyReopen(Instant requestedAt) {
        ResponseEntity<String> response = controlEventService.applyReopen(eventId,
                new ReopenApplicationRequest(UUID.randomUUID().toString(), requestedAt));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private JsonNode confirmReopen(ReopenRole role, String operatorId) {
        ResponseEntity<String> response = controlEventService.confirmReopen(eventId,
                new ReopenConfirmationRequest(UUID.randomUUID().toString(), role, operatorId));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody());
    }

    private void feedLowWind() {
        for (long station : new long[]{STATION_1, STATION_2, STATION_3, STATION_4}) {
            feedMinutes(station, TRIGGER_AT.plusSeconds(60), 15, 12.0);
        }
    }

    private String eventStatus() {
        return jdbc.queryForObject("SELECT status FROM control_event WHERE id = ?", String.class, eventId);
    }
}
