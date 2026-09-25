package com.example.starter.slaughter;

import com.example.starter.slaughter.domain.InspectionResult;
import com.example.starter.slaughter.dto.DeclareRequest;
import com.example.starter.slaughter.dto.DeclareResponse;
import com.example.starter.slaughter.dto.InspectionItem;
import com.example.starter.slaughter.dto.InspectionRegisterRequest;
import com.example.starter.slaughter.service.ServiceOutcome;
import com.example.starter.slaughter.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H2 真实并发边界：并发申报唯一受理、同 requestId 并发唯一受理并返回首次结果、
 * 同批次并发开链只存在一条检验链。使用真实线程协调，不做休眠断言。
 */
class ConcurrencyTest extends AbstractSlaughterTest {

    private final List<String> heads = List.of("H001", "H002", "H003", "H004");

    @Test
    void concurrentDeclareSameBatch_onlyOneAccepted() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    await(start);
                    try {
                        ServiceOutcome<DeclareResponse> out = service.declare(
                                new DeclareRequest("conc-declare-" + idx, "B-CONC-1", heads));
                        if (out.httpStatus() == 201) {
                            created.incrementAndGet();
                        }
                    } catch (BusinessException ex) {
                        if ("BATCH_ALREADY_DECLARED".equals(ex.getCode())) {
                            conflicts.incrementAndGet();
                        } else {
                            throw new AssertionError("unexpected code: " + ex.getCode(), ex);
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, created.get(), "并发申报必须唯一受理");
        assertEquals(threads - 1, conflicts.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM slaughter_batch WHERE batch_no = 'B-CONC-1'", Integer.class));
        assertEquals(heads.size(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-CONC-1'", Integer.class));
    }

    @Test
    void concurrentSameRequestIdExactlyOneExecution_bothGetFirstResult() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    await(start);
                    ServiceOutcome<DeclareResponse> out = service.declare(
                            new DeclareRequest("same-request-id", "B-CONC-2", heads));
                    if (out.replayed()) {
                        replays.incrementAndGet();
                    } else {
                        firstExecutions.incrementAndGet();
                    }
                    assertEquals("B-CONC-2", out.data().batchNo());
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, firstExecutions.get(), "同 requestId 并发只能有一笔实际执行");
        assertEquals(threads - 1, replays.get(), "其余均返回首次结果");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM slaughter_batch WHERE batch_no = 'B-CONC-2'", Integer.class));
    }

    @Test
    void concurrentInspectionRegistration_singleChainAndAllHeadsCounted() throws Exception {
        declareAndPass("conc-chain", "B-CONC-3", heads);

        int pairs = 2;
        ExecutorService pool = Executors.newFixedThreadPool(pairs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        try {
            futures.add(pool.submit(() -> {
                await(start);
                registerWithRetry("B-CONC-3", "conc-chain-req-a",
                        List.of(new InspectionItem("H001", InspectionResult.NORMAL),
                                new InspectionItem("H002", InspectionResult.NORMAL)));
            }));
            futures.add(pool.submit(() -> {
                await(start);
                registerWithRetry("B-CONC-3", "conc-chain-req-b",
                        List.of(new InspectionItem("H003", InspectionResult.SUSPECT),
                                new InspectionItem("H004", InspectionResult.NORMAL)));
            }));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 同一批次同时刻只存在一条检验链，4 头全部一一对应登记。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_chain WHERE batch_no = 'B-CONC-3'", Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT inspected_count FROM inspection_chain WHERE batch_no = 'B-CONC-3'",
                Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-CONC-3' "
                        + "AND inspection_result IS NOT NULL", Integer.class));
    }

    @Test
    void concurrentSameRequestIdInspection_onlyOneChainExecution() throws Exception {
        declareAndPass("conc-same", "B-CONC-4", List.of("H001", "H002"));
        List<InspectionItem> items = List.of(
                new InspectionItem("H001", InspectionResult.NORMAL),
                new InspectionItem("H002", InspectionResult.SUSPECT));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    await(start);
                    var out = service.registerInspection("B-CONC-4",
                            new InspectionRegisterRequest("conc-same-req", items));
                    if (out.replayed()) {
                        replays.incrementAndGet();
                    } else {
                        firstExecutions.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, firstExecutions.get());
        assertEquals(threads - 1, replays.get());
        assertEquals(2, jdbc.queryForObject(
                "SELECT inspected_count FROM inspection_chain WHERE batch_no = 'B-CONC-4'",
                Integer.class));
        assertEquals("INSPECTING", jdbc.queryForObject(
                "SELECT status FROM slaughter_batch WHERE batch_no = 'B-CONC-4'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-CONC-4' "
                        + "AND judgment_category IS NOT NULL", Integer.class));
    }

    /** 并发开链冲突为可重试 409 时按指数退避式短暂重试，直到成功或出现其他错误。 */
    private void registerWithRetry(String batchNo, String requestId, List<InspectionItem> items) {
        BusinessException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                service.registerInspection(batchNo,
                        new InspectionRegisterRequest(requestId, items));
                return;
            } catch (BusinessException ex) {
                last = ex;
                if (!"BATCH_STATE_CHANGED".equals(ex.getCode())) {
                    throw ex;
                }
                try {
                    Thread.sleep(10L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ie);
                }
            }
        }
        throw new AssertionError("并发登记重试后仍失败", last);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
