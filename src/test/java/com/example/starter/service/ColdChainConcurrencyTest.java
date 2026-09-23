package com.example.starter.service;

import com.example.starter.data.ColdChainRepository;
import com.example.starter.domain.SegmentStatus;
import com.example.starter.web.ConflictException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.HandoverRequest;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ShipmentCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与幂等边界测试（H2 真实事务 + shipment 行锁）。
 * 覆盖：并发写读数、并发交接（同键同参/同键异参）、写读数与交接并发的一致提交顺序。
 * 所有用例对任务完成顺序不敏感：通过闩锁同时放行，断言两种合法提交顺序下的最终状态。
 */
@SpringBootTest
class ColdChainConcurrencyTest {

    private static final long MINUTE = 60_000L;
    private static final long T0 = 1_700_000_000_000L;

    @Autowired
    private ColdChainService service;

    @Autowired
    private ColdChainRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private void createShipment(String id) {
        service.createShipment(new ShipmentCreateRequest(
                id, new BigDecimal("2.0"), new BigDecimal("8.0"), 10, 15,
                "carrier-A", T0));
    }

    private static ReadingRequest reading(long seq, long offsetMinutes, String temp) {
        return new ReadingRequest(seq, T0 + offsetMinutes * MINUTE, new BigDecimal(temp));
    }

    private static HandoverRequest handover(String requestId, int version, String custodian,
                                            String receiver, long offsetMinutes, long lastSeq) {
        return new HandoverRequest(requestId, version, custodian, receiver,
                T0 + offsetMinutes * MINUTE, lastSeq);
    }

    /** 并发执行 tasks，全部在 30 秒内完成，返回每个任务的结果或异常。 */
    private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> wrapped = new ArrayList<>();
        for (Callable<T> task : tasks) {
            wrapped.add(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS), "start latch timeout");
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            });
        }
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> c : wrapped) {
                futures.add(pool.submit(c));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "ready latch timeout");
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(60)
    void concurrentHandoversSameKeySameParamsFreezeOneResult() throws Exception {
        createShipment("CC-1");
        service.addReading("CC-1", reading(1, 0, "4.0"));

        int threads = 6;
        List<Callable<HandoverOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.handover("CC-1",
                    handover("REQ-X", 0, "carrier-A", "carrier-B", 10, 1)));
        }
        List<Object> results = runConcurrently(tasks);

        int fresh = 0;
        int replayed = 0;
        for (Object r : results) {
            assertTrue(r instanceof HandoverOutcome, "unexpected: " + r);
            HandoverOutcome o = (HandoverOutcome) r;
            assertEquals(SegmentStatus.CLEAR, o.status());
            assertEquals(0, o.closedVersion());
            if (o.replayed()) {
                replayed++;
            } else {
                fresh++;
            }
        }
        assertEquals(1, fresh, "exactly one handover may commit first");
        assertEquals(threads - 1, replayed);

        // 只关闭了一段、只推进了一次版本
        var evidence = service.evidence("CC-1");
        assertEquals(1, evidence.shipment().version());
        assertEquals(2, evidence.segments().size());
        assertEquals(SegmentStatus.CLEAR, evidence.segments().get(0).segment().status());
        assertEquals(SegmentStatus.OPEN, evidence.segments().get(1).segment().status());
    }

    @Test
    @Timeout(60)
    void concurrentHandoversSameKeyDifferentParamsConflict() throws Exception {
        createShipment("CC-2");
        service.addReading("CC-2", reading(1, 0, "4.0"));

        List<Callable<HandoverOutcome>> tasks = new ArrayList<>();
        // 一半同参，一半改 lastSequence（同键异参）
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.handover("CC-2",
                    handover("REQ-Y", 0, "carrier-A", "carrier-B", 10, 1)));
        }
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.handover("CC-2",
                    handover("REQ-Y", 0, "carrier-A", "carrier-B", 10, 2)));
        }
        List<Object> results = runConcurrently(tasks);

        AtomicInteger committed = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (Object r : results) {
            if (r instanceof HandoverOutcome) {
                committed.incrementAndGet();
            } else {
                assertTrue(r instanceof ConflictException, "unexpected: " + r);
                conflicts.incrementAndGet();
            }
        }
        // 同键异参：只有一组参数能胜出（3 个同参提交/重放），另一组全部 409
        assertEquals(3, committed.get());
        assertEquals(3, conflicts.get());
        assertEquals(1, service.evidence("CC-2").shipment().version());
    }

    @Test
    @Timeout(60)
    void concurrentReadingsDistinctSequencesAllPersist() throws Exception {
        createShipment("CC-3");
        int threads = 8;
        List<Callable<ReadingResult>> tasks = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            long seq = i;
            tasks.add(() -> service.addReading("CC-3", reading(seq, seq, "4.0")));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object r : results) {
            assertTrue(r instanceof ReadingResult, "unexpected: " + r);
        }
        var readings = repository.findReadingsBetween("CC-3", 1, threads);
        assertEquals(threads, readings.size());
    }

    @Test
    @Timeout(60)
    void concurrentSameSequenceSameParamsReplayDifferentParamsConflict() throws Exception {
        createShipment("CC-4");
        // 先建立基线读数，消除并发写入顺序对结果的影响
        service.addReading("CC-4", reading(1, 0, "4.0"));

        List<Callable<ReadingResult>> tasks = new ArrayList<>();
        // 同序号同参 x3：全部重放
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.addReading("CC-4", reading(1, 0, "4.0")));
        }
        // 同序号异参 x2：409
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> service.addReading("CC-4", reading(1, 0, "9.9")));
        }
        List<Object> results = runConcurrently(tasks);

        int ok = 0;
        int conflicts = 0;
        for (Object r : results) {
            if (r instanceof ReadingResult) {
                ok++;
            } else {
                assertTrue(r instanceof ConflictException, "unexpected: " + r);
                conflicts++;
            }
        }
        assertEquals(3, ok);
        assertEquals(2, conflicts);
        // 最终数据一致：序号 1 只有一条，值为首次写入的 4.0
        var stored = repository.findReading("CC-4", 1);
        assertEquals(0, stored.temperatureC().compareTo(new BigDecimal("4.0")));
    }

    @Test
    @Timeout(60)
    void concurrentReadingAndHandoverFormConsistentCommitOrder() throws Exception {
        createShipment("CC-5");
        service.addReading("CC-5", reading(1, 0, "4.0"));
        service.addReading("CC-5", reading(2, 5, "5.0"));

        // 一个交接（覆盖到序号 2），并发一个写入序号 3；两种提交顺序都必须一致
        List<Callable<Object>> tasks = List.of(
                () -> service.handover("CC-5", handover("REQ-Z", 0, "carrier-A", "carrier-B", 10, 2)),
                () -> service.addReading("CC-5", reading(3, 12, "5.0")));
        List<Object> results = runConcurrently(tasks);

        // 读数在两种顺序下都成功（交接先提交时落在新段；读数先提交时落在旧打开段）
        assertTrue(results.get(1) instanceof ReadingResult, "unexpected: " + results.get(1));
        var evidence = service.evidence("CC-5");
        if (results.get(0) instanceof HandoverOutcome) {
            // 交接先提交：序号 3 落在交接后打开的新段，已关闭段证据只含 1、2
            assertEquals(1, evidence.shipment().version());
            var closed = evidence.segments().get(0);
            assertEquals(SegmentStatus.CLEAR, closed.segment().status());
            assertEquals(2, closed.readings().size());
            var open = evidence.segments().get(1);
            assertEquals(SegmentStatus.OPEN, open.segment().status());
            assertEquals(1, open.readings().size());
            assertEquals(3L, open.readings().get(0).sequenceNo());
        } else {
            // 读数先提交：交接因 lastSequence 落后于已收序号被 422 拒绝，无部分交接
            assertTrue(results.get(0) instanceof UnprocessableException,
                    "unexpected: " + results.get(0));
            assertEquals(0, evidence.shipment().version());
            assertEquals(1, evidence.segments().size());
            assertEquals(SegmentStatus.OPEN, evidence.segments().get(0).segment().status());
            assertEquals(3, evidence.segments().get(0).readings().size());
        }
    }
}
