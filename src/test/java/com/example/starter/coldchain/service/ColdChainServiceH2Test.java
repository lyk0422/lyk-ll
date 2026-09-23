package com.example.starter.coldchain.service;

import com.example.starter.coldchain.domain.SegmentStatus;
import com.example.starter.coldchain.dto.CreateShipmentRequest;
import com.example.starter.coldchain.dto.HandoverRequest;
import com.example.starter.coldchain.dto.HandoverResponse;
import com.example.starter.coldchain.dto.ReadingRequest;
import com.example.starter.coldchain.dto.ReadingResponse;
import com.example.starter.coldchain.dto.SegmentEvidenceResponse;
import com.example.starter.coldchain.entity.Shipment;
import com.example.starter.coldchain.entity.TransportSegment;
import com.example.starter.coldchain.exception.ConflictException;
import com.example.starter.coldchain.exception.ValidationException;
import com.example.starter.coldchain.repository.ColdChainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实 H2（MODE=MySQL）的服务/事务/并发集成测试，不 mock 数据库边界。
 */
@SpringBootTest
class ColdChainServiceH2Test {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 23, 8, 0);

    @Autowired
    private ColdChainService service;
    @Autowired
    private ColdChainRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM handover_record");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM transport_segment");
        jdbc.update("DELETE FROM shipment");
    }

    private String newShipment() {
        String id = "S-" + UUID.randomUUID();
        service.createShipment(new CreateShipmentRequest(
                id, 2.0, 8.0, 10, 300, "Carrier-A", T0));
        return id;
    }

    private ReadingRequest reading(long seq, LocalDateTime t, double temp) {
        return new ReadingRequest(seq, "DEV-1", t, temp);
    }

    @Test
    void outOfOrderReadingsThenClearHandover() {
        String id = newShipment();
        // 乱序到达：3、1、2。
        service.writeReading(id, reading(3, T0.plusMinutes(10), 5.0));
        service.writeReading(id, reading(1, T0, 5.0));
        service.writeReading(id, reading(2, T0.plusMinutes(5), 6.0));

        HandoverResponse resp = service.handover(id, new HandoverRequest(
                "REQ-1", 1, "Carrier-B", T0.plusMinutes(10), 3L));
        assertFalse(resp.replayed());
        assertEquals(SegmentStatus.CLEAR, resp.status());
        assertEquals(1, resp.closedVersion());
        assertEquals(2, resp.newVersion());

        List<SegmentEvidenceResponse> evidence = service.segmentEvidence(id);
        assertEquals(2, evidence.size());
        SegmentEvidenceResponse v1 = evidence.get(0);
        assertEquals(SegmentStatus.CLEAR, v1.status());
        // 证据按序号升序返回，即使写入是乱序的。
        assertEquals(List.of(1L, 2L, 3L),
                v1.readings().stream().map(SegmentEvidenceResponse.ReadingView::sequenceNo).toList());
        assertEquals(SegmentStatus.OPEN, evidence.get(1).status());
        assertEquals(4L, evidence.get(1).firstSequence());
    }

    @Test
    void thresholdEndpointsAreClear() {
        String id = newShipment();
        service.writeReading(id, reading(1, T0, 2.0));
        service.writeReading(id, reading(2, T0.plusMinutes(5), 8.0));
        HandoverResponse resp = service.handover(id, new HandoverRequest(
                "REQ-EP", 1, "Carrier-B", T0.plusMinutes(5), 2L));
        assertEquals(SegmentStatus.CLEAR, resp.status());
    }

    @Test
    void continuousExcursionIsExcursion() {
        String id = newShipment();
        // 9.0 从 t0 持续到 t0+10min1s（平台 10 分 1 秒 > 10 分钟上限）。
        service.writeReading(id, reading(1, T0, 9.0));
        service.writeReading(id, reading(2, T0.plusMinutes(5), 9.0));
        service.writeReading(id, reading(3, T0.plusMinutes(10), 9.0));
        service.writeReading(id, reading(4, T0.plusMinutes(10).plusSeconds(1), 5.0));
        HandoverResponse resp = service.handover(id, new HandoverRequest(
                "REQ-EX", 1, "Carrier-B", T0.plusMinutes(10).plusSeconds(1), 4L));
        assertEquals(SegmentStatus.EXCURSION, resp.status());
    }

    @Test
    void missingSequenceFreezesIncompleteAtomicallyAndRejectsBackfill() {
        String id = newShipment();
        service.writeReading(id, reading(1, T0, 5.0));
        service.writeReading(id, reading(2, T0.plusMinutes(5), 5.0));
        // 声明末序号 3，但缺少序号 3 → 冻结为 INCOMPLETE（单事务原子关闭+新开）。
        HandoverResponse resp = service.handover(id, new HandoverRequest(
                "REQ-INC", 1, "Carrier-B", T0.plusMinutes(10), 3L));
        assertEquals(SegmentStatus.INCOMPLETE, resp.status());

        // 原子性：旧段已关闭为 INCOMPLETE、新段已打开、版本推进到 2，全部同时生效。
        Shipment shipment = service.getShipment(id);
        assertEquals(2, shipment.currentVersion());
        TransportSegment v1 = repository.findSegment(id, 1);
        assertEquals(SegmentStatus.INCOMPLETE, v1.status());
        assertEquals(3L, v1.lastSequence());
        TransportSegment v2 = repository.findSegment(id, 2);
        assertEquals(SegmentStatus.OPEN, v2.status());
        assertEquals(4L, v2.firstSequence());

        // 已关闭段（含 INCOMPLETE 缺口）拒绝补写其序号范围：序号 3 落在 v1 的 1..3。
        assertThrows(ValidationException.class,
                () -> service.writeReading(id, reading(3, T0.plusMinutes(10), 5.0)));
    }

    @Test
    void invalidHandoverRollsBackWithoutPartialEffect() {
        String id = newShipment();
        service.writeReading(id, reading(1, T0, 5.0));

        // 时间倒退（交接时间早于本段开始时间）→ 422，整体回滚。
        assertThrows(ValidationException.class, () -> service.handover(id, new HandoverRequest(
                "REQ-BAD-TIME", 1, "Carrier-B", T0.minusMinutes(1), 1L)));
        // 保管方不匹配（与当前保管方相同）→ 422，整体回滚。
        assertThrows(ValidationException.class, () -> service.handover(id, new HandoverRequest(
                "REQ-BAD-CUST", 1, "Carrier-A", T0.plusMinutes(5), 1L)));
        // 版本冲突 → 409，整体回滚。
        assertThrows(ConflictException.class, () -> service.handover(id, new HandoverRequest(
                "REQ-BAD-VER", 9, "Carrier-B", T0.plusMinutes(5), 1L)));

        // 交接时间早于段内末条读数采样时间 → 422，整体回滚。
        service.writeReading(id, reading(2, T0.plusMinutes(5), 5.0));
        assertThrows(ValidationException.class, () -> service.handover(id, new HandoverRequest(
                "REQ-BAD-READING-TIME", 1, "Carrier-B", T0.plusMinutes(4), 2L)));

        // 无任何部分交接：版本仍为 1、仅一个开放段、无残留交接记录。
        assertEquals(1, service.getShipment(id).currentVersion());
        assertEquals(1, repository.findAllSegments(id).size());
        assertEquals(SegmentStatus.OPEN, repository.findOpenSegment(id).status());
        assertNull(repository.findHandover("REQ-BAD-TIME"));
        assertNull(repository.findHandover("REQ-BAD-CUST"));
        assertNull(repository.findHandover("REQ-BAD-VER"));
        assertNull(repository.findHandover("REQ-BAD-READING-TIME"));

        // 合法交接仍可进行。
        HandoverResponse resp = service.handover(id, new HandoverRequest(
                "REQ-OK", 1, "Carrier-B", T0.plusMinutes(5), 1L));
        assertEquals(SegmentStatus.CLEAR, resp.status());
        assertEquals(2, service.getShipment(id).currentVersion());
    }

    @Test
    void sameSequenceReplayReturnsOriginalAndChangedValueConflicts() {
        String id = newShipment();
        ReadingResponse first = service.writeReading(id, reading(1, T0, 5.0));
        assertFalse(first.replayed());

        // 同序号同参重放。
        ReadingResponse replay = service.writeReading(id, reading(1, T0, 5.0));
        assertTrue(replay.replayed());
        assertEquals(5.0, replay.temperature());

        // 改温度 → 409；改时间 → 409；改设备 → 409。
        assertThrows(ConflictException.class,
                () -> service.writeReading(id, reading(1, T0, 5.1)));
        assertThrows(ConflictException.class,
                () -> service.writeReading(id, reading(1, T0.plusSeconds(1), 5.0)));
        assertThrows(ConflictException.class,
                () -> service.writeReading(id,
                        new ReadingRequest(1L, "DEV-2", T0, 5.0)));
    }

    @Test
    void closedSegmentRejectsBackfill() {
        String id = newShipment();
        service.writeReading(id, reading(1, T0, 5.0));
        service.writeReading(id, reading(3, T0.plusMinutes(5), 5.0));
        // 缺序号 2，冻结为 INCOMPLETE，v1 区间声明为 1..3。
        HandoverResponse resp = service.handover(id, new HandoverRequest(
                "REQ-CLOSE", 1, "Carrier-B", T0.plusMinutes(5), 3L));
        assertEquals(SegmentStatus.INCOMPLETE, resp.status());
        // 向已关闭 v1 的序号范围补写缺失序号 2 → 拒绝。
        assertThrows(ValidationException.class,
                () -> service.writeReading(id, reading(2, T0.plusMinutes(2), 5.0)));
        // 新段序号 4 仍可正常写入。
        ReadingResponse next = service.writeReading(id, reading(4, T0.plusMinutes(6), 5.0));
        assertFalse(next.replayed());
    }

    @Test
    void timeRegressionRejectedEvenWhenSequenceArrivesLate() {
        String id = newShipment();
        service.writeReading(id, reading(3, T0.plusMinutes(10), 5.0));
        service.writeReading(id, reading(1, T0, 5.0));
        // 序号 2 的采样时间晚于序号 3 → 时间倒退。
        assertThrows(ValidationException.class,
                () -> service.writeReading(id, reading(2, T0.plusMinutes(11), 5.0)));
    }

    @Test
    void invalidTemperatureAndCustodianAndVersionChecks() {
        String id = newShipment();
        service.writeReading(id, reading(1, T0, 5.0));
        // 非有限温度。
        assertThrows(ValidationException.class,
                () -> service.writeReading(id, reading(2, T0.plusMinutes(5), Double.NaN)));
        // 保管方与当前相同。
        assertThrows(ValidationException.class, () -> service.handover(id, new HandoverRequest(
                "REQ-SAME", 1, "Carrier-A", T0.plusMinutes(5), 1L)));
        // 版本不匹配。
        assertThrows(ConflictException.class, () -> service.handover(id, new HandoverRequest(
                "REQ-VER", 2, "Carrier-B", T0.plusMinutes(5), 1L)));
    }

    @Test
    void handoverIdempotentReplayAndFingerprintConflict() {
        String id = newShipment();
        service.writeReading(id, reading(1, T0, 5.0));
        HandoverRequest first = new HandoverRequest(
                "REQ-IDEM", 1, "Carrier-B", T0.plusMinutes(5), 1L);
        HandoverResponse r1 = service.handover(id, first);
        assertFalse(r1.replayed());

        // 同键同参重放：返回首次冻结结果。
        HandoverResponse r2 = service.handover(id, first);
        assertTrue(r2.replayed());
        assertEquals(r1.status(), r2.status());
        assertEquals(r1.newVersion(), r2.newVersion());

        // 同键异参：lastSequence 变化 → 409。
        assertThrows(ConflictException.class, () -> service.handover(id, new HandoverRequest(
                "REQ-IDEM", 1, "Carrier-B", T0.plusMinutes(5), 2L)));
        // 同键异参：expectedVersion 变化 → 409。
        assertThrows(ConflictException.class, () -> service.handover(id, new HandoverRequest(
                "REQ-IDEM", 2, "Carrier-B", T0.plusMinutes(5), 1L)));
    }

    @Test
    void concurrentDistinctReadingsAndHandoverFormConsistentOrder() throws Exception {
        // 多次迭代以提高交错覆盖。
        for (int iter = 0; iter < 10; iter++) {
            String id = newShipment();
            final String requestId = "REQ-C-" + iter;
            service.writeReading(id, reading(1, T0, 5.0));
            service.writeReading(id, reading(2, T0.plusMinutes(2), 5.0));
            service.writeReading(id, reading(3, T0.plusMinutes(4), 5.0));

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ReadingResponse> writeFuture = pool.submit(() -> {
                    barrier.await();
                    return service.writeReading(id, reading(4, T0.plusMinutes(6), 5.0));
                });
                Future<HandoverResponse> handoverFuture = pool.submit(() -> {
                    barrier.await();
                    return service.handover(id, new HandoverRequest(
                            requestId, 1, "Carrier-B", T0.plusMinutes(6), 3L));
                });
                writeFuture.get(30, TimeUnit.SECONDS);
                HandoverResponse hr = handoverFuture.get(30, TimeUnit.SECONDS);
                assertEquals(SegmentStatus.CLEAR, hr.status());
            } finally {
                pool.shutdownNow();
            }

            // 无论谁先拿到行锁，最终一致：v1 关闭且只含 1..3，v2 开放首序号 4，读数 4 归属 v2。
            List<SegmentEvidenceResponse> evidence = service.segmentEvidence(id);
            SegmentEvidenceResponse v1 = evidence.get(0);
            SegmentEvidenceResponse v2 = evidence.get(1);
            assertEquals(SegmentStatus.CLEAR, v1.status());
            assertEquals(3L, v1.lastSequence());
            assertEquals(List.of(1L, 2L, 3L),
                    v1.readings().stream().map(SegmentEvidenceResponse.ReadingView::sequenceNo).toList());
            assertEquals(SegmentStatus.OPEN, v2.status());
            assertEquals(4L, v2.firstSequence());
            assertEquals(List.of(4L),
                    v2.readings().stream().map(SegmentEvidenceResponse.ReadingView::sequenceNo).toList());
            assertEquals(2, service.getShipment(id).currentVersion());
        }
    }

    @Test
    void concurrentSameRequestIdHandoverFreezesExactlyOnce() throws Exception {
        String id = newShipment();
        service.writeReading(id, reading(1, T0, 5.0));
        HandoverRequest req = new HandoverRequest(
                "REQ-DUP", 1, "Carrier-B", T0.plusMinutes(5), 1L);

        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<HandoverResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return service.handover(id, req);
                }));
            }
            int replayed = 0;
            int first = 0;
            for (Future<HandoverResponse> f : futures) {
                HandoverResponse r = f.get(30, TimeUnit.SECONDS);
                assertEquals(SegmentStatus.CLEAR, r.status());
                assertEquals(2, r.newVersion());
                if (r.replayed()) {
                    replayed++;
                } else {
                    first++;
                }
            }
            assertEquals(1, first, "应有且仅有一个请求首次冻结结果");
            assertEquals(threads - 1, replayed, "其余请求必须返回首次冻结结果");
        } finally {
            pool.shutdownNow();
        }
        // 只打开了一个新段，没有重复交接。
        assertEquals(2, repository.findAllSegments(id).size());
        assertEquals(2, service.getShipment(id).currentVersion());
    }

    @Test
    void concurrentSameSequenceSameParamsOneInsertOthersReplay() throws Exception {
        String id = newShipment();
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ReadingResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return service.writeReading(id, reading(1, T0, 5.0));
                }));
            }
            int inserts = 0;
            int replays = 0;
            for (Future<ReadingResponse> f : futures) {
                ReadingResponse r = f.get(30, TimeUnit.SECONDS);
                if (r.replayed()) {
                    replays++;
                } else {
                    inserts++;
                }
            }
            assertEquals(1, inserts);
            assertEquals(threads - 1, replays);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, repository.findReadingsBetween(id, 1, 1).size());
    }
}
