package com.example.starter;

import com.example.starter.service.ApiException;
import com.example.starter.web.dto.WindReadingRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 风速阈值判定测试：恰等阈值按高档处理、恰满 10 分钟才触发、去重与乱序归位。
 */
class WindThresholdTest extends BaseIntegrationTest {

    @Test
    void exactly17TriggersSlowStopOnlyAfterFullTenMinutes() {
        // 分段 3（风口站-山顶站）双站持续上报 17.0 m/s
        for (int i = 0; i <= 9; i++) {
            ingest(STATION_3, T0.plusSeconds(60L * i), 17.0);
            JsonNode resp = ingest(STATION_4, T0.plusSeconds(60L * i), 17.0);
            assertThat(resp.get("triggeredEvent").isNull()).as("第 %s 分钟不应触发", i).isTrue();
        }
        assertThat(lineStatus()).isEqualTo("NORMAL");
        assertThat(countRows("control_event")).isZero();

        // 恰满 10 分钟且平均恰达 17m/s：触发缓停（站点 3 数据到达时窗口已齐）
        JsonNode resp = ingest(STATION_3, T0.plusSeconds(600), 17.0);
        JsonNode event = resp.get("triggeredEvent");
        assertThat(event).isNotNull();
        assertThat(event.get("type").asText()).isEqualTo("SLOW_STOP");
        assertThat(event.get("segmentId").asLong()).isEqualTo(SEGMENT_3);
        assertThat(event.get("avgSpeed").asDouble()).isEqualTo(17.0);
        // 已有进行中事件，另一站数据到达不再触发
        JsonNode after = ingest(STATION_4, T0.plusSeconds(600), 17.0);
        assertThat(after.get("triggeredEvent").isNull()).isTrue();
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");
        assertThat(countRows("control_event")).isEqualTo(1);
    }

    @Test
    void exactly20TriggersEmergencyStop() {
        feedMinutes(STATION_3, T0, 9, 20.0);
        feedMinutes(STATION_4, T0, 9, 20.0);
        JsonNode resp = ingest(STATION_3, T0.plusSeconds(600), 20.0);
        JsonNode event = resp.get("triggeredEvent");
        assertThat(event).isNotNull();
        assertThat(event.get("type").asText()).isEqualTo("EMERGENCY_STOP");
        assertThat(event.get("avgSpeed").asDouble()).isEqualTo(20.0);
        assertThat(lineStatus()).isEqualTo("EMERGENCY_STOP");
    }

    @Test
    void belowThresholdDoesNotTrigger() {
        feedMinutes(STATION_3, T0, 10, 16.9);
        JsonNode resp = feedMinutes(STATION_4, T0, 10, 16.9);
        assertThat(resp.get("triggeredEvent").isNull()).isTrue();
        assertThat(lineStatus()).isEqualTo("NORMAL");
        assertThat(countRows("control_event")).isZero();
    }

    @Test
    void singleGustDoesNotTrigger() {
        // 10 分钟窗口内仅一个 25m/s 阵风点，其余 10m/s，平均远低于阈值
        for (int i = 0; i <= 10; i++) {
            double speed = i == 5 ? 25.0 : 10.0;
            ingest(STATION_3, T0.plusSeconds(60L * i), speed);
            ingest(STATION_4, T0.plusSeconds(60L * i), speed);
        }
        assertThat(lineStatus()).isEqualTo("NORMAL");
        assertThat(countRows("control_event")).isZero();
    }

    @Test
    void sameStationSameTimeIsDeduplicated() {
        JsonNode first = ingest(STATION_1, T0, 8.0);
        JsonNode second = ingest(STATION_1, T0, 8.0);
        assertThat(second.get("deduplicated").asBoolean()).isTrue();
        assertThat(second.get("readingId").asLong()).isEqualTo(first.get("readingId").asLong());
        assertThat(countRows("wind_reading")).isEqualTo(1);
    }

    @Test
    void sameStationSameTimeWithDifferentSpeedConflicts() {
        ingest(STATION_1, T0, 8.0);
        assertThatThrownBy(() -> windIngestionService.ingest(LINE_ID, new WindReadingRequest(
                UUID.randomUUID().toString(), STATION_1, T0, 9.5)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("wind_reading")).isEqualTo(1);
    }

    @Test
    void outOfOrderReadingIsFiledByTimestampWithoutAffectingTriggeredEvent() {
        feedMinutes(STATION_3, T0, 10, 17.0);
        JsonNode resp = feedMinutes(STATION_4, T0, 10, 17.0);
        assertThat(resp.get("triggeredEvent")).isNotNull();
        assertThat(countRows("control_event")).isEqualTo(1);

        // 乱序补一条窗口内的历史数据（未上报过的时刻）：按时刻归位入库，不触发新判定
        JsonNode late = ingest(STATION_3, T0.plusSeconds(330), 30.0);
        assertThat(late.get("outOfOrder").asBoolean()).isTrue();
        assertThat(late.get("triggeredEvent").isNull()).isTrue();
        assertThat(countRows("control_event")).isEqualTo(1);
        assertThat(countRows("wind_reading")).isEqualTo(23);
    }

    @Test
    void stationOfOtherLineIsRejected() {
        assertThatThrownBy(() -> windIngestionService.ingest(LINE_ID, new WindReadingRequest(
                UUID.randomUUID().toString(), 999L, T0, 8.0)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
