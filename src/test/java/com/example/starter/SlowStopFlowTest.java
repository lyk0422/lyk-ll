package com.example.starter;

import com.example.starter.service.ApiException;
import com.example.starter.web.dto.BoardingRequest;
import com.example.starter.web.dto.CabinReleaseRequest;
import com.example.starter.web.dto.ReopenApplicationRequest;
import com.example.starter.web.dto.ReopenConfirmationRequest;
import com.example.starter.model.ReopenRole;
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
 * 缓停全流程测试：禁止上客、逐段放行、恰满 15 分钟复开申请、双岗确认复开。
 */
class SlowStopFlowTest extends BaseIntegrationTest {

    private static final Instant TRIGGER_AT = T0.plusSeconds(600);

    private long eventId;

    @BeforeEach
    void triggerSlowStop() {
        feedMinutes(STATION_3, T0, 9, 17.0);
        feedMinutes(STATION_4, T0, 9, 17.0);
        JsonNode resp = ingest(STATION_3, TRIGGER_AT, 17.0);
        ingest(STATION_4, TRIGGER_AT, 17.0);
        eventId = resp.get("triggeredEvent").get("eventId").asLong();
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");
    }

    @Test
    void boardingIsForbiddenDuringSlowStop() {
        assertThatThrownBy(() -> controlEventService.board(LINE_ID,
                new BoardingRequest(UUID.randomUUID().toString(), 2)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("boarding")).isZero();
    }

    @Test
    void cabinsAreReleasedSegmentBySegment() {
        JsonNode first = releaseCabins(SEGMENT_1, "op-1");
        assertThat(first.get("released").asBoolean()).isTrue();
        // 同一分段重复放行：保持已放行状态，不新增记录
        JsonNode again = releaseCabins(SEGMENT_1, "op-2");
        assertThat(again.get("released").asBoolean()).isTrue();
        assertThat(countRows("cabin_release")).isEqualTo(1);

        releaseCabins(SEGMENT_2, "op-1");
        releaseCabins(SEGMENT_3, "op-1");
        assertThat(countRows("cabin_release")).isEqualTo(3);
    }

    @Test
    void reopenRequiresFullFifteenMinutesBelow15() {
        // 风速自触发后下一分钟回落至 14m/s
        for (long station : new long[]{STATION_1, STATION_2, STATION_3, STATION_4}) {
            feedMinutes(station, TRIGGER_AT.plusSeconds(60), 14, 14.0);
        }
        // 恰满 15 分钟前申请：拒绝，且事务回滚不留该请求的幂等记录
        String failingRequestId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> controlEventService.applyReopen(eventId,
                new ReopenApplicationRequest(failingRequestId, TRIGGER_AT.plusSeconds(15 * 60))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Integer recorded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?",
                Integer.class, failingRequestId);
        assertThat(recorded).isZero();
        assertThat(eventStatus()).isEqualTo("ACTIVE");

        // 补第 16 分钟数据后恰满 15 分钟：允许申请
        for (long station : new long[]{STATION_1, STATION_2, STATION_3, STATION_4}) {
            ingest(station, TRIGGER_AT.plusSeconds(16 * 60), 14.0);
        }
        JsonNode resp = applyReopen(TRIGGER_AT.plusSeconds(16 * 60));
        assertThat(resp.get("status").asText()).isEqualTo("REOPEN_REQUESTED");
        assertThat(eventStatus()).isEqualTo("REOPEN_REQUESTED");
    }

    @Test
    void reopenFailsWhenWindNotBelow15() {
        for (long station : new long[]{STATION_1, STATION_2, STATION_3, STATION_4}) {
            feedMinutes(station, TRIGGER_AT.plusSeconds(60), 15, 15.0);
        }
        // 恰为 15m/s 不属于“15m/s 以下”
        assertThatThrownBy(() -> applyReopen(TRIGGER_AT.plusSeconds(16 * 60)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void reopenRequiresBothRolesAndArchivesEvent() {
        feedLowWindAndApply();

        JsonNode ops = confirmReopen(ReopenRole.OPERATIONS, "ops-1");
        assertThat(ops.get("resumed").asBoolean()).isFalse();
        assertThat(ops.get("operationsConfirmed").asBoolean()).isTrue();
        assertThat(ops.get("maintenanceConfirmed").asBoolean()).isFalse();
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");

        // 同一岗位重复确认不推进流程
        JsonNode opsAgain = confirmReopen(ReopenRole.OPERATIONS, "ops-2");
        assertThat(opsAgain.get("resumed").asBoolean()).isFalse();
        assertThat(countRows("reopen_confirmation")).isEqualTo(1);

        JsonNode maint = confirmReopen(ReopenRole.MAINTENANCE, "mt-1");
        assertThat(maint.get("resumed").asBoolean()).isTrue();
        assertThat(eventStatus()).isEqualTo("RESUMED");
        assertThat(lineStatus()).isEqualTo("NORMAL");
        // 归档为终态：归档时间已写入
        JsonNode event = objectMapper.valueToTree(controlEventService.eventView(eventId));
        assertThat(event.get("archivedAt").isNull()).isFalse();

        // 复开后允许上客
        ResponseEntity<String> boarding = controlEventService.board(LINE_ID,
                new BoardingRequest(UUID.randomUUID().toString(), 4));
        assertThat(boarding.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void lateWindDataAfterArchiveOnlyRecordsLateEvent() {
        feedLowWindAndApply();
        confirmReopen(ReopenRole.OPERATIONS, "ops-1");
        confirmReopen(ReopenRole.MAINTENANCE, "mt-1");
        assertThat(eventStatus()).isEqualTo("RESUMED");

        // 归档后到达的、不晚于触发时刻的迟到数据：只记迟到事件，不再判定
        JsonNode late = ingest(STATION_3, T0.plusSeconds(90), 25.0);
        assertThat(late.get("late").asBoolean()).isTrue();
        assertThat(late.get("triggeredEvent").isNull()).isTrue();
        assertThat(countRows("late_data_event")).isEqualTo(1);
        assertThat(countRows("control_event")).isEqualTo(1);
        assertThat(lineStatus()).isEqualTo("NORMAL");
    }

    private JsonNode releaseCabins(long segmentId, String operatorId) {
        ResponseEntity<String> response = controlEventService.releaseCabins(eventId, segmentId,
                new CabinReleaseRequest(UUID.randomUUID().toString(), operatorId));
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

    private void feedLowWindAndApply() {
        for (long station : new long[]{STATION_1, STATION_2, STATION_3, STATION_4}) {
            feedMinutes(station, TRIGGER_AT.plusSeconds(60), 15, 14.0);
        }
        applyReopen(TRIGGER_AT.plusSeconds(16 * 60));
        assertThat(eventStatus()).isEqualTo("REOPEN_REQUESTED");
    }

    private String eventStatus() {
        return jdbc.queryForObject("SELECT status FROM control_event WHERE id = ?", String.class, eventId);
    }
}
