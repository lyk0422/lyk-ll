package com.example.starter;

import java.math.BigDecimal;

import com.example.starter.dto.GrainDtos.CreateJobRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.JobView;
import com.example.starter.dto.GrainDtos.ReadingRequest;
import com.example.starter.dto.GrainDtos.ReadingResponse;
import com.example.starter.dto.GrainDtos.RecheckRequest;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 温湿度上报与告警判定测试：安全温度恰等边界、超温告警、幂等重放与归档后迟到事件。
 */
class SiloReadingTest extends AbstractIntegrationTest {

    @Test
    void temperatureEqualToSafeLimitIsNormal() {
        SiloView silo = newSilo("S-EQ");

        ReadingResponse response = siloService.ingestReading(silo.id(), new ReadingRequest(
                "r-eq-1", 1, new BigDecimal("30.00"), new BigDecimal("60.00"), T0));

        assertEquals("NORMAL", response.result());
        assertNull(response.alarmId());
        assertFalse(response.replayed());
        assertEquals(SiloStatus.MONITORING, response.siloStatus());
        assertEquals(0, countRows("alarm"));
        assertEquals(1, countRows("sensor_reading"));
    }

    @Test
    void temperatureAboveSafeLimitRaisesAlarm() {
        SiloView silo = newSilo("S-AL");

        ReadingResponse response = siloService.ingestReading(silo.id(), new ReadingRequest(
                "r-al-1", 2, new BigDecimal("30.01"), new BigDecimal("60.00"), T0));

        assertEquals("ALARM", response.result());
        assertNotNull(response.alarmId());
        assertEquals(SiloStatus.ALARMING, response.siloStatus());
        assertEquals(1, countRows("alarm"));
        assertEquals(SiloStatus.ALARMING, siloService.getSilo(silo.id()).status());
    }

    @Test
    void replaySameReadingReturnsFirstResult() {
        SiloView silo = newSilo("S-RP");
        ReadingRequest request = new ReadingRequest("r-rp-1", 1,
                new BigDecimal("31.50"), new BigDecimal("60.00"), T0);

        ReadingResponse first = siloService.ingestReading(silo.id(), request);
        ReadingResponse replay = siloService.ingestReading(silo.id(), request);

        assertEquals("ALARM", first.result());
        assertTrue(replay.replayed());
        assertEquals(first.alarmId(), replay.alarmId());
        assertEquals(1, countRows("sensor_reading"));
        assertEquals(1, countRows("alarm"));
    }

    @Test
    void sameKeyWithDifferentParamsConflicts() {
        SiloView silo = newSilo("S-CF");
        siloService.ingestReading(silo.id(), new ReadingRequest("r-cf-1", 1,
                new BigDecimal("31.50"), new BigDecimal("60.00"), T0));

        ApiException ex = assertThrows(ApiException.class, () -> siloService.ingestReading(silo.id(),
                new ReadingRequest("r-cf-1", 1, new BigDecimal("32.50"), new BigDecimal("60.00"), T0)));

        assertEquals(409, ex.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
        assertEquals(1, countRows("sensor_reading"));
    }

    @Test
    void lateReadingAfterArchiveOnlyRecordsLateEvent() {
        SiloView silo = newSilo("S-LT");
        FanView fan = newFan("F-LT");
        raiseAlarm(silo.id(), "r-lt-alarm");
        JobView job = ventilationService.createJob(silo.id(),
                new CreateJobRequest("j-lt-1", fan.id(), com.example.starter.model.VentilationPurpose.COOLING,
                        T0.plusHours(2), T0.plusHours(3), new BigDecimal("65.00")));
        ventilationService.completeJob(job.id());
        ventilationService.recheck(job.id(), new RecheckRequest("rc-lt-1",
                new BigDecimal("4.00"), new BigDecimal("5.00"), T0.plusHours(4)));
        ventilationService.archive(job.id());
        assertEquals(SiloStatus.ARCHIVED, siloService.getSilo(silo.id()).status());

        long readingsBefore = countRows("sensor_reading");
        ReadingResponse late = siloService.ingestReading(silo.id(), new ReadingRequest(
                "r-lt-late", 3, new BigDecimal("35.00"), new BigDecimal("60.00"), T0.plusHours(5)));

        assertEquals("LATE_RECORDED", late.result());
        assertNull(late.alarmId());
        assertEquals(readingsBefore, countRows("sensor_reading"));
        assertEquals(1, countRows("late_event"));

        // 迟到事件重放同键同参仍为幂等成功，不重复记录
        ReadingResponse replay = siloService.ingestReading(silo.id(), new ReadingRequest(
                "r-lt-late", 3, new BigDecimal("35.00"), new BigDecimal("60.00"), T0.plusHours(5)));
        assertEquals("LATE_RECORDED", replay.result());
        assertEquals(1, countRows("late_event"));
    }

    @Test
    void temperatureCurveIsRecomputable() {
        SiloView silo = newSilo("S-TC");
        siloService.ingestReading(silo.id(), new ReadingRequest("r-tc-1", 1,
                new BigDecimal("28.00"), null, T0.plusMinutes(10)));
        siloService.ingestReading(silo.id(), new ReadingRequest("r-tc-2", 1,
                new BigDecimal("29.00"), null, T0));
        siloService.ingestReading(silo.id(), new ReadingRequest("r-tc-3", 2,
                new BigDecimal("27.50"), null, T0.plusMinutes(5)));

        var curve = siloService.temperatureCurve(silo.id());

        assertEquals(3, curve.size());
        assertEquals(new BigDecimal("29.00"), curve.get(0).temperature());
        assertEquals(new BigDecimal("27.50"), curve.get(1).temperature());
        assertEquals(new BigDecimal("28.00"), curve.get(2).temperature());
    }
}
