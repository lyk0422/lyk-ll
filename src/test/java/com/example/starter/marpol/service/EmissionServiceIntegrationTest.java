package com.example.starter.marpol.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.marpol.MarpolIntegrationTestBase;
import com.example.starter.marpol.support.ApiException;
import com.example.starter.marpol.web.DischargeAmendRequest;
import com.example.starter.marpol.web.DischargeSubmitRequest;
import com.example.starter.marpol.web.RecordResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 排放记录：区域边界、12 海里口径、版本化修正、幂等重放/异参、并发版本唯一、航次关闭。
 */
class EmissionServiceIntegrationTest extends MarpolIntegrationTestBase {

    private static final Instant EVENT_TIME = Instant.parse("2026-09-01T08:00:00Z");
    /** 合成岸线参考点 P-SYN-01 (30,122)：沿经线向北恰 12 海里的纬度。 */
    private static final double EARTH_RADIUS_NM = 6_371_000.0 / 1852.0;
    private static final BigDecimal LAT_EXACT_12_NM =
            BigDecimal.valueOf(30 + Math.toDegrees(12.0 / EARTH_RADIUS_NM));
    private static final BigDecimal LAT_11_9_NM =
            BigDecimal.valueOf(30 + Math.toDegrees(11.9 / EARTH_RADIUS_NM));

    @Autowired
    private EmissionService emissionService;

    @Autowired
    private ShipService shipService;

    private DischargeSubmitRequest oilRequest(String requestId, BigDecimal lat, BigDecimal lon) {
        return new DischargeSubmitRequest(requestId, "SHIP-001", "V-2026-01", "OIL",
                lat, lon, EVENT_TIME, new BigDecimal("2.500"), "m3");
    }

    private DischargeSubmitRequest garbageRequest(String requestId, BigDecimal lat) {
        return new DischargeSubmitRequest(requestId, "SHIP-001", "V-2026-01", "GARBAGE",
                lat, new BigDecimal("122.000000"), EVENT_TIME, new BigDecimal("10.000"), "kg");
    }

    private DischargeSubmitRequest sewageRequest(String requestId, BigDecimal lat,
                                                 BigDecimal lon) {
        return new DischargeSubmitRequest(requestId, "SHIP-001", "V-2026-01", "SEWAGE",
                lat, lon, EVENT_TIME, new BigDecimal("1.000"), "m3");
    }

    @Test
    void oilInsideSpecialAreaIsNonCompliantAndBoundaryCountsInside() {
        RecordResponse inside = emissionService.submit(
                oilRequest("REQ-OIL-IN", new BigDecimal("25"), new BigDecimal("115")));
        assertFalse(inside.compliant());
        assertEquals(1, inside.version());

        // 恰在特殊区域边界线上：视为区域内，油类禁止
        RecordResponse onEdge = emissionService.submit(
                oilRequest("REQ-OIL-EDGE", new BigDecimal("25"), new BigDecimal("110")));
        assertFalse(onEdge.compliant());

        // 区域外油类排放合规
        RecordResponse outside = emissionService.submit(
                oilRequest("REQ-OIL-OUT", new BigDecimal("5"), new BigDecimal("115")));
        assertTrue(outside.compliant());
    }

    @Test
    void sewageInsideSpecialAreaIncludingBoundaryIsProhibited() {
        RecordResponse inside = emissionService.submit(
                sewageRequest("REQ-SEW-IN", new BigDecimal("25"), new BigDecimal("115")));
        assertFalse(inside.compliant());
        RecordResponse onEdge = emissionService.submit(
                sewageRequest("REQ-SEW-EDGE", new BigDecimal("20"), new BigDecimal("115")));
        assertFalse(onEdge.compliant(), "生活污水在区域边界线上也应禁止");
        RecordResponse outside = emissionService.submit(
                sewageRequest("REQ-SEW-OUT", new BigDecimal("5"), new BigDecimal("115")));
        assertTrue(outside.compliant());
    }

    @Test
    void garbageAtExactly12NmIsLegalAndCloserIsNot() {
        RecordResponse exact = emissionService.submit(garbageRequest("REQ-G12", LAT_EXACT_12_NM));
        assertTrue(exact.compliant(), "恰 12 海里应合法: " + exact.complianceDetail());

        RecordResponse tooClose = emissionService.submit(garbageRequest("REQ-G11", LAT_11_9_NM));
        assertFalse(tooClose.compliant(), "不足 12 海里应不合规: " + tooClose.complianceDetail());
    }

    @Test
    void sameRequestSamePayloadReplaysAndDifferentPayloadConflicts() {
        RecordResponse first = emissionService.submit(
                oilRequest("REQ-IDEM", new BigDecimal("5"), new BigDecimal("115")));
        assertFalse(first.idempotentReplay());

        RecordResponse replay = emissionService.submit(
                oilRequest("REQ-IDEM", new BigDecimal("5"), new BigDecimal("115")));
        assertTrue(replay.idempotentReplay());
        assertEquals(first.recordNo(), replay.recordNo());
        assertEquals(first.version(), replay.version());
        assertEquals(1, emissionService.listVersions(first.recordNo()).size(),
                "同键同参重放不得产生新版本");

        ApiException conflict = assertThrows(ApiException.class, () -> emissionService.submit(
                oilRequest("REQ-IDEM", new BigDecimal("6"), new BigDecimal("115"))));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals(1, emissionService.listVersions(first.recordNo()).size(),
                "同键异参 409 且不产生新版本");
    }

    @Test
    void amendCreatesNewVersionKeepsOriginalAndDefaultQueryReturnsLatest() {
        RecordResponse first = emissionService.submit(
                oilRequest("REQ-ORIG", new BigDecimal("5"), new BigDecimal("115")));

        DischargeAmendRequest amend = new DischargeAmendRequest("REQ-AMEND-1",
                first.recordNo(), 1, "SHIP-001", "V-2026-01", "OIL",
                new BigDecimal("6"), new BigDecimal("115"), EVENT_TIME,
                new BigDecimal("3.000"), "m3");
        RecordResponse second = emissionService.amend(amend);
        assertEquals(2, second.version());
        assertEquals(first.recordNo(), second.recordNo());
        assertEquals(first.recordNo(), second.supersedesRecordNo());
        assertEquals(1, second.supersedesVersion());

        RecordResponse latest = emissionService.getRecord(first.recordNo(), null);
        assertEquals(2, latest.version());
        assertEquals("ACTIVE", latest.status());

        RecordResponse original = emissionService.getRecord(first.recordNo(), 1);
        assertEquals(1, original.version());
        assertEquals("SUPERSEDED", original.status());
        assertNotNull(original.createdAt());
    }

    @Test
    void amendWithSameAmendRequestIdIsIdempotent() {
        RecordResponse first = emissionService.submit(
                oilRequest("REQ-A0", new BigDecimal("5"), new BigDecimal("115")));
        DischargeAmendRequest amend = new DischargeAmendRequest("REQ-A1",
                first.recordNo(), 1, "SHIP-001", "V-2026-01", "OIL",
                new BigDecimal("6"), new BigDecimal("115"), EVENT_TIME,
                new BigDecimal("3.000"), "m3");
        RecordResponse second = emissionService.amend(amend);
        RecordResponse replay = emissionService.amend(amend);
        assertTrue(replay.idempotentReplay());
        assertEquals(2, replay.version());
        assertEquals(2, emissionService.listVersions(first.recordNo()).size());

        DischargeAmendRequest altered = new DischargeAmendRequest("REQ-A1",
                first.recordNo(), 1, "SHIP-001", "V-2026-01", "OIL",
                new BigDecimal("7"), new BigDecimal("115"), EVENT_TIME,
                new BigDecimal("3.000"), "m3");
        ApiException conflict = assertThrows(ApiException.class,
                () -> emissionService.amend(altered));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
    }

    @Test
    void concurrentAmendsAllowOnlyOneWinner() throws Exception {
        RecordResponse first = emissionService.submit(
                oilRequest("REQ-C0", new BigDecimal("5"), new BigDecimal("115")));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    // 所有并发修正都基于版本 1：仅一个胜出，其余 409
                    DischargeAmendRequest amend = new DischargeAmendRequest("REQ-C" + (idx + 1),
                            first.recordNo(), 1, "SHIP-001", "V-2026-01", "OIL",
                            BigDecimal.valueOf(6 + idx), new BigDecimal("115"), EVENT_TIME,
                            new BigDecimal("3.000"), "m3");
                    emissionService.amend(amend);
                    success.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getStatus() == HttpStatus.CONFLICT) {
                        conflict.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "并发修正只允许一个版本胜出");
        assertEquals(threads - 1, conflict.get(), "失败者应返回 409");
        List<RecordResponse> versions = emissionService.listVersions(first.recordNo());
        assertEquals(2, versions.size(), "仅允许新增一个新版本");
        assertEquals(2, emissionService.getRecord(first.recordNo(), null).version());
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM emission_record WHERE record_no = ? AND status = 'ACTIVE'",
                Integer.class, first.recordNo());
        assertEquals(1, activeCount, "只能有一个 ACTIVE 版本");
    }

    @Test
    void sequentialAmendsChainOnLatestVersion() {
        RecordResponse first = emissionService.submit(
                oilRequest("REQ-S0", new BigDecimal("5"), new BigDecimal("115")));
        RecordResponse v2 = emissionService.amend(new DischargeAmendRequest("REQ-S1",
                first.recordNo(), 1, "SHIP-001", "V-2026-01", "OIL",
                new BigDecimal("6"), new BigDecimal("115"), EVENT_TIME,
                new BigDecimal("3.000"), "m3"));
        assertEquals(2, v2.version());
        // 基于旧版本 1 再修正应被拒绝
        ApiException stale = assertThrows(ApiException.class, () -> emissionService.amend(
                new DischargeAmendRequest("REQ-S2-STALE", first.recordNo(), 1,
                        "SHIP-001", "V-2026-01", "OIL", new BigDecimal("8"),
                        new BigDecimal("115"), EVENT_TIME, new BigDecimal("3.000"), "m3")));
        assertEquals(HttpStatus.CONFLICT, stale.getStatus());
        // 基于最新版本 2 可继续修正
        RecordResponse v3 = emissionService.amend(new DischargeAmendRequest("REQ-S3",
                first.recordNo(), 2, "SHIP-001", "V-2026-01", "OIL",
                new BigDecimal("8"), new BigDecimal("115"), EVENT_TIME,
                new BigDecimal("4.000"), "m3"));
        assertEquals(3, v3.version());
        assertEquals(2, v3.supersedesVersion());
        assertEquals(3, emissionService.listVersions(first.recordNo()).size());
    }

    @Test
    void submissionAfterVoyageClosedIsRejected() {
        shipService.closeVoyage("SHIP-001", "V-2026-01");
        ApiException ex = assertThrows(ApiException.class, () -> emissionService.submit(
                oilRequest("REQ-CLOSED", new BigDecimal("5"), new BigDecimal("115"))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertNull(emissionService.listActive("SHIP-001", "V-2026-01").stream()
                .filter(r -> "REQ-CLOSED".equals(r.requestId())).findFirst().orElse(null));
    }

    @Test
    void concurrentSameRequestSubmitCreatesSingleRecord() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inserted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    RecordResponse r = emissionService.submit(
                            oilRequest("REQ-RACE-SAME", new BigDecimal("5"), new BigDecimal("115")));
                    if (!r.idempotentReplay()) {
                        inserted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, inserted.get(), "同键并发首提只应插入一条记录");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM emission_record WHERE request_id = 'REQ-RACE-SAME'",
                Integer.class);
        assertEquals(1, count);
    }
}
