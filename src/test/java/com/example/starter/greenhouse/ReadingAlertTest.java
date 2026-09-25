package com.example.starter.greenhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.greenhouse.domain.AlertStatus;
import com.example.starter.greenhouse.dto.AlertAckRequest;
import com.example.starter.greenhouse.dto.AlertAckResponse;
import com.example.starter.greenhouse.dto.ReadingIngestRequest;
import com.example.starter.greenhouse.dto.ReadingResponse;
import com.example.starter.greenhouse.repo.DeviationAlertRepository;
import com.example.starter.greenhouse.service.AlertService;
import com.example.starter.greenhouse.service.ReadingService;
import com.example.starter.greenhouse.web.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 读数去重归位、连续恰 3 点偏离触发、边界内不触发、告警确认幂等。
 * 白天设定带：温度 [21,23]℃，湿度 [65,75]%RH（采样时刻取 UTC 10:00-15:00）。
 */
class ReadingAlertTest extends BaseGreenhouseTest {

    @Autowired
    private ReadingService readingService;
    @Autowired
    private AlertService alertService;
    @Autowired
    private DeviationAlertRepository alertRepository;

    private void setupZoneWithDayStrategy(String crop, String zone, String strategy) {
        createCrop(crop);
        createZone(zone);
        draftValidate(strategy, crop, zone,
                "22", "1", "70", "5", "16", "1", "70", "5");
        strategyService.activate(strategy, "REQ-" + strategy);
    }

    private ReadingResponse ingest(String zone, String time, String temp, String hum) {
        return readingService.ingest(new ReadingIngestRequest(
                zone, time, new BigDecimal(temp), new BigDecimal(hum)));
    }

    @Test
    void 恰连续3个偏离采样点才触发告警() {
        setupZoneWithDayStrategy("C1", "Z1", "S1");
        assertFalse(ingest("Z1", "2026-09-25T10:00:00Z", "30.00", "70").alertTriggered(), "第1点不触发");
        assertFalse(ingest("Z1", "2026-09-25T11:00:00Z", "30.00", "70").alertTriggered(), "第2点不触发");
        ReadingResponse third = ingest("Z1", "2026-09-25T12:00:00Z", "30.00", "70");
        assertTrue(third.alertTriggered(), "恰第3点触发");
        assertNotNull(third.alertId());
        // 第4个偏离点不得重复触发。
        assertFalse(ingest("Z1", "2026-09-25T13:00:00Z", "30.00", "70").alertTriggered());
        long alertCount = jdbc.queryForObject("SELECT COUNT(*) FROM deviation_alert WHERE zone_id = "
                + "(SELECT id FROM zone WHERE zone_code='Z1')", Long.class);
        assertEquals(1L, alertCount);
    }

    @Test
    void 恰在设定带边界不算偏离() {
        setupZoneWithDayStrategy("C2", "Z2", "S2");
        // 温度恰为上界 23.00、湿度恰为下界 65.00，含边界合法。
        ReadingResponse r = ingest("Z2", "2026-09-25T10:00:00Z", "23.00", "65.00");
        assertFalse(r.alertTriggered());
        // 越界 0.01 即偏离；连续 3 点触发。
        ingest("Z2", "2026-09-25T11:00:00Z", "23.01", "70");
        ingest("Z2", "2026-09-25T12:00:00Z", "23.01", "70");
        ReadingResponse third = ingest("Z2", "2026-09-25T13:00:00Z", "23.01", "70");
        assertTrue(third.alertTriggered());
    }

    @Test
    void 乱序上报按时刻归位且仅触发一次() {
        setupZoneWithDayStrategy("C3", "Z3", "S3");
        // 乱序：先报 12:00，再报 10:00、11:00，三点全偏离，补齐第 3 点时触发一次。
        assertFalse(ingest("Z3", "2026-09-25T12:00:00Z", "30.00", "70").alertTriggered());
        assertFalse(ingest("Z3", "2026-09-25T10:00:00Z", "30.00", "70").alertTriggered());
        ReadingResponse closed = ingest("Z3", "2026-09-25T11:00:00Z", "30.00", "70");
        assertTrue(closed.alertTriggered());
        // 触发时刻为时间序第 3 点 12:00。
        java.sql.Timestamp triggerTime = jdbc.queryForObject(
                "SELECT trigger_sample_time FROM deviation_alert WHERE zone_id = "
                        + "(SELECT id FROM zone WHERE zone_code='Z3')", java.sql.Timestamp.class);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM deviation_alert WHERE zone_id = "
                        + "(SELECT id FROM zone WHERE zone_code='Z3')", Integer.class).intValue());
        assertEquals(java.sql.Timestamp.from(java.time.Instant.parse("2026-09-25T12:00:00Z")), triggerTime);
    }

    @Test
    void 同分区同时刻重复读数去重() {
        setupZoneWithDayStrategy("C4", "Z4", "S4");
        ReadingResponse first = ingest("Z4", "2026-09-25T10:00:00Z", "30.00", "70");
        ReadingResponse dup = ingest("Z4", "2026-09-25T10:00:00Z", "30.00", "70");
        assertTrue(dup.deduped());
        assertEquals(first.id(), dup.id());
        long rows = jdbc.queryForObject("SELECT COUNT(*) FROM sensor_reading WHERE zone_id = "
                + "(SELECT id FROM zone WHERE zone_code='Z4')", Long.class);
        assertEquals(1L, rows);
    }

    @Test
    void 回到设定带内后未恢复告警被关闭() {
        setupZoneWithDayStrategy("C5", "Z5", "S5");
        ingest("Z5", "2026-09-25T10:00:00Z", "30.00", "70");
        ingest("Z5", "2026-09-25T11:00:00Z", "30.00", "70");
        ReadingResponse triggered = ingest("Z5", "2026-09-25T12:00:00Z", "30.00", "70");
        assertNotNull(triggered.alertId());
        assertNull(alertRepository.findById(triggered.alertId()).orElseThrow().getRecoveredAt());
        ingest("Z5", "2026-09-25T13:00:00Z", "22.00", "70");
        assertNotNull(alertRepository.findById(triggered.alertId()).orElseThrow().getRecoveredAt());
    }

    @Test
    void 告警确认同键同参重放成功异参409() {
        setupZoneWithDayStrategy("C6", "Z6", "S6");
        ingest("Z6", "2026-09-25T10:00:00Z", "30.00", "70");
        ingest("Z6", "2026-09-25T11:00:00Z", "30.00", "70");
        ReadingResponse t1 = ingest("Z6", "2026-09-25T12:00:00Z", "30.00", "70");
        Long alert1 = t1.alertId();

        AlertAckResponse ack = alertService.acknowledge(new AlertAckRequest(alert1, "ACK-1"));
        assertEquals(AlertStatus.ACKNOWLEDGED.name(), ack.status());
        AlertAckResponse replay = alertService.acknowledge(new AlertAckRequest(alert1, "ACK-1"));
        assertEquals(ack.acknowledgedAt(), replay.acknowledgedAt());
        assertEquals(AlertStatus.ACKNOWLEDGED,
                alertRepository.findById(alert1).orElseThrow().getStatus());

        // 制造第二个告警：恢复后再连续偏离 3 点。
        ingest("Z6", "2026-09-25T13:00:00Z", "22.00", "70");
        ingest("Z6", "2026-09-25T14:00:00Z", "30.00", "70");
        ingest("Z6", "2026-09-25T15:00:00Z", "30.00", "70");
        ReadingResponse t2 = ingest("Z6", "2026-09-25T16:00:00Z", "30.00", "70");
        // 16:00 UTC 仍按白天带判定（hour<18）。
        assertTrue(t2.alertTriggered());
        ApiException ex = assertThrows(ApiException.class,
                () -> alertService.acknowledge(new AlertAckRequest(t2.alertId(), "ACK-1")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void 确认不存在的告警返回404() {
        ApiException ex = assertThrows(ApiException.class,
                () -> alertService.acknowledge(new AlertAckRequest(999999L, "ACK-X")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
