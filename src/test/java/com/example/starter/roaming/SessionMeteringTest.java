package com.example.starter.roaming;

import com.example.starter.roaming.api.AuthorizeRequest;
import com.example.starter.roaming.api.AuthorizeResponse;
import com.example.starter.roaming.api.EndSessionRequest;
import com.example.starter.roaming.api.MeterSnapshotRequest;
import com.example.starter.roaming.api.MeterSnapshotResponse;
import com.example.starter.roaming.api.SessionResponse;
import com.example.starter.roaming.api.StartSessionRequest;
import com.example.starter.roaming.error.ApiException;
import com.example.starter.roaming.service.AuthorizationService;
import com.example.starter.roaming.service.ChargingSessionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话启停与计量测试：状态机、快照去重与乱序归位、重复不重计、requestId 幂等与冲突。
 */
class SessionMeteringTest extends AbstractRoamingTest {

    @Autowired
    private AuthorizationService authorizationService;
    @Autowired
    private ChargingSessionService sessionService;

    /** 授权并启动一个跨网会话（车属 OP-A，桩属 OP-B），返回会话号。 */
    private String authorizeAndStart(String authId, String startId) {
        Instant t = clock.instant();
        AuthorizeResponse auth = authorizationService.authorize(
                new AuthorizeRequest(authId, "FP-CAR-0001", "PILE-B-001", t));
        SessionResponse started = sessionService.start(
                new StartSessionRequest(startId, auth.sessionNo(), null, null, null));
        assertEquals("CHARGING", started.status());
        return auth.sessionNo();
    }

    private MeterSnapshotRequest meter(String id, int seq, String energy, Instant at) {
        return new MeterSnapshotRequest(id, seq, new BigDecimal(energy), at);
    }

    @Test
    void snapshotsDeduplicateAndReorderBySeqWithoutDoubleCounting() {
        String sessionNo = authorizeAndStart("A1", "S1");
        Instant t = clock.instant();

        // 乱序到达：序号 1 先到。
        MeterSnapshotResponse r1 = sessionService.meter(sessionNo, meter("M1", 1, "10.000", t.plusSeconds(60)));
        assertFalse(r1.deduplicated());
        assertEquals(0, new BigDecimal("10.000").compareTo(r1.totalEnergyKwh()));

        // 序号 0 后到，归位但不改变“最新读数”。
        MeterSnapshotResponse r0 = sessionService.meter(sessionNo, meter("M0", 0, "0.000", t.plusSeconds(30)));
        assertFalse(r0.deduplicated());
        assertEquals(0, new BigDecimal("10.000").compareTo(r0.totalEnergyKwh()));

        // 序号 1 重复上传：去重，不重计。
        MeterSnapshotRequest dup = meter("M1-DUP", 1, "10.000", t.plusSeconds(60));
        MeterSnapshotResponse r1dup = sessionService.meter(sessionNo, dup);
        assertTrue(r1dup.deduplicated());
        assertEquals(0, new BigDecimal("10.000").compareTo(r1dup.totalEnergyKwh()));

        // 序号 2 到达，最新读数推进到 25。
        MeterSnapshotResponse r2 = sessionService.meter(sessionNo, meter("M2", 2, "25.000", t.plusSeconds(120)));
        assertEquals(0, new BigDecimal("25.000").compareTo(r2.totalEnergyKwh()));

        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM meter_snapshot", Integer.class));
    }

    @Test
    void sameMeterRequestIdReplaysFirstResult() {
        String sessionNo = authorizeAndStart("A1", "S1");
        Instant t = clock.instant();
        MeterSnapshotRequest m = meter("METER-ID", 0, "5.000", t.plusSeconds(30));
        MeterSnapshotResponse first = sessionService.meter(sessionNo, m);
        MeterSnapshotResponse again = sessionService.meter(sessionNo, m);

        assertTrue(again.replayed());
        assertEquals(first.readingSeq(), again.readingSeq());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM meter_snapshot", Integer.class));
    }

    @Test
    void sameMeterRequestIdDifferentParamsReturns409AndCreatesNoSnapshot() {
        String sessionNo = authorizeAndStart("A1", "S1");
        Instant t = clock.instant();
        sessionService.meter(sessionNo, meter("CONFLICT-ID", 0, "5.000", t.plusSeconds(30)));

        ApiException ex = assertThrows(ApiException.class,
                () -> sessionService.meter(sessionNo, meter("CONFLICT-ID", 1, "9.000", t.plusSeconds(40))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM meter_snapshot", Integer.class));
    }

    @Test
    void duplicateSeqWithDifferentEnergyIsConflict() {
        String sessionNo = authorizeAndStart("A1", "S1");
        Instant t = clock.instant();
        sessionService.meter(sessionNo, meter("M1", 0, "5.000", t.plusSeconds(30)));

        ApiException ex = assertThrows(ApiException.class,
                () -> sessionService.meter(sessionNo, meter("M2", 0, "6.000", t.plusSeconds(30))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(0, new BigDecimal("5.000").compareTo(
                jdbc.queryForObject("SELECT energy_kwh FROM meter_snapshot WHERE reading_seq = 0",
                        BigDecimal.class)));
    }

    @Test
    void nonMonotonicReadingIsRejectedAndRolledBack() {
        String sessionNo = authorizeAndStart("A1", "S1");
        Instant t = clock.instant();
        sessionService.meter(sessionNo, meter("M1", 1, "10.000", t.plusSeconds(60)));

        ApiException ex = assertThrows(ApiException.class,
                () -> sessionService.meter(sessionNo, meter("M2", 2, "9.000", t.plusSeconds(90))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        // 倒退快照随事务回滚，不落库。
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM meter_snapshot", Integer.class));
    }

    @Test
    void endSessionUsesLatestReadingAndIsIdempotent() {
        String sessionNo = authorizeAndStart("A1", "S1");
        Instant t = clock.instant();
        sessionService.meter(sessionNo, meter("M1", 0, "5.000", t.plusSeconds(30)));
        sessionService.meter(sessionNo, meter("M2", 1, "12.500", t.plusSeconds(60)));

        clock.advance(Duration.ofMinutes(10));
        Instant end = clock.instant();
        SessionResponse ended = sessionService.end(sessionNo, new EndSessionRequest("E1", end));
        assertEquals("ENDED", ended.status());
        assertEquals(0, new BigDecimal("12.500").compareTo(ended.totalEnergyKwh()));

        // 同 requestId 重放结束：返回首次结果，不重复处理。
        SessionResponse replay = sessionService.end(sessionNo, new EndSessionRequest("E1", end));
        assertTrue(replay.replayed());
    }

    @Test
    void endBeforeStartIsRejected() {
        Instant t = clock.instant();
        AuthorizeResponse auth = authorizationService.authorize(
                new AuthorizeRequest("A1", "FP-CAR-0001", "PILE-B-001", t));
        // 未启动直接结束。
        ApiException ex = assertThrows(ApiException.class,
                () -> sessionService.end(auth.sessionNo(), new EndSessionRequest("E1", t.plusSeconds(10))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void directStartWithFingerprintEnforcesUniqueSessionPerVehicleAndTime() {
        Instant t = clock.instant();
        sessionService.start(new StartSessionRequest("D1", null, "FP-CAR-0002", "PILE-A-001", t));
        ApiException ex = assertThrows(ApiException.class,
                () -> sessionService.start(new StartSessionRequest("D2", null, "FP-CAR-0002", "PILE-A-001", t)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Integer.class));
    }

    @Test
    void meterOnUnknownSessionIs404() {
        ApiException ex = assertThrows(ApiException.class,
                () -> sessionService.meter("NO-SESS", meter("M1", 0, "1.000", clock.instant())));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
