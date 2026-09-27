package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.AcquisitionBatchResult;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 采集并发与幂等边界测试：并发上传相邻包不重复消耗前缀、不多算返工；
 * 同 requestId 并发上传只入账一次。
 */
class ConcurrentAcquisitionServiceTest extends AbstractServiceTest {

    @Autowired
    private OperationService operationService;

    @Autowired
    private AcquisitionService acquisitionService;

    private void prepareSession(String cardNo, String station, String sessionNo) {
        operationService.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        operationService.dispatch(TestRequests.dispatch("d-" + cardNo, cardNo, station, "N"));
        Outcome created = acquisitionService.createSession(
                TestRequests.acqSession("s-" + sessionNo, sessionNo, cardNo, "T1"));
        assertThat(created.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("并发上传相邻包（1 与 2）：前缀只消耗一次，力矩链与水位线恰好推进到 2")
    void concurrentAdjacentBatches() throws InterruptedException {
        prepareSession("CARD-CA1", "S-CA1", "SES-CA1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger versionConflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        try {
            // 两个线程携带各自版本期望上传相邻序号；败方版本冲突后重试
            pool.submit(() -> uploadWithRetry(ready, start, ok, versionConflict, other,
                    "b-ca1-a", 1, "P1", "2026-09-26T08:00:01Z", "100.000"));
            pool.submit(() -> uploadWithRetry(ready, start, ok, versionConflict, other,
                    "b-ca1-b", 2, "P2", "2026-09-26T08:00:02Z", "50.000"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(other.get()).isZero();
        assertThat(ok.get()).isEqualTo(2);
        // 水位线恰好到 2，力矩链每点恰 1 条（前缀未被重复消耗）
        assertThat(acquisitionService.getWatermark("SES-CA1").watermark()).isEqualTo(2);
        assertThat(operationService.trace("CARD-CA1").torqueCurve()).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_packet", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_batch", Integer.class)).isEqualTo(2);
    }

    /** 版本冲突时按最新版本重试，模拟客户端读取-上传循环。 */
    private void uploadWithRetry(CountDownLatch ready, CountDownLatch start, AtomicInteger ok,
                                 AtomicInteger versionConflict, AtomicInteger other,
                                 String requestIdPrefix, int seq, String point,
                                 String measuredAt, String torque) {
        ready.countDown();
        try {
            start.await();
            for (int attempt = 0; attempt < 10; attempt++) {
                long version = acquisitionService.getSession("SES-CA1").version();
                Outcome outcome = acquisitionService.uploadBatch("SES-CA1", TestRequests.batch(
                        requestIdPrefix + "-" + attempt, version,
                        TestRequests.packet(seq, point, measuredAt, torque)));
                if (outcome.status() == 200) {
                    ok.incrementAndGet();
                    return;
                }
                if (outcome.status() == 409) {
                    versionConflict.incrementAndGet();
                    continue;
                }
                other.incrementAndGet();
                return;
            }
            other.incrementAndGet();
        } catch (Exception e) {
            other.incrementAndGet();
        }
    }

    @Test
    @DisplayName("并发上传相邻超差包：返工次数不多算，attempt 链连续无重复")
    void concurrentReworkNotDoubleCounted() throws InterruptedException {
        prepareSession("CARD-CA2", "S-CA2", "SES-CA2");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        try {
            // P1 两次相邻超差包并发：应恰好入账为 attempt 1、2，返工 1、2
            pool.submit(() -> uploadReworkWithRetry(ready, start, done, other,
                    "b-ca2-a", 1, "2026-09-26T08:00:01Z"));
            pool.submit(() -> uploadReworkWithRetry(ready, start, done, other,
                    "b-ca2-b", 2, "2026-09-26T08:00:02Z"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(other.get()).isZero();
        assertThat(done.get()).isEqualTo(2);
        assertThat(acquisitionService.getWatermark("SES-CA2").watermark()).isEqualTo(2);
        assertThat(operationService.trace("CARD-CA2").torqueCurve()).hasSize(2);
        // 返工台账恰好 2 条且编号 1、2 各一次（不多算）
        assertThat(operationService.trace("CARD-CA2").reworkLedger())
                .extracting(com.example.starter.api.dto.TraceView.ReworkPoint::reworkNo)
                .containsExactly(1, 2);
    }

    private void uploadReworkWithRetry(CountDownLatch ready, CountDownLatch start, AtomicInteger done,
                                       AtomicInteger other, String requestIdPrefix, int seq,
                                       String measuredAt) {
        ready.countDown();
        try {
            start.await();
            for (int attempt = 0; attempt < 10; attempt++) {
                long version = acquisitionService.getSession("SES-CA2").version();
                Outcome outcome = acquisitionService.uploadBatch("SES-CA2", TestRequests.batch(
                        requestIdPrefix + "-" + attempt, version,
                        TestRequests.packet(seq, "P1", measuredAt, "120.000")));
                if (outcome.status() == 200) {
                    done.incrementAndGet();
                    return;
                }
                if (outcome.status() == 409) {
                    continue;
                }
                other.incrementAndGet();
                return;
            }
            other.incrementAndGet();
        } catch (Exception e) {
            other.incrementAndGet();
        }
    }

    @Test
    @DisplayName("同 requestId 同参并发上传：只入账一次，双方均获首次结果")
    void concurrentSameRequestIdUploadsOnce() throws InterruptedException {
        prepareSession("CARD-CA3", "S-CA3", "SES-CA3");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        Outcome outcome = acquisitionService.uploadBatch("SES-CA3",
                                TestRequests.batch("b-ca3-same", 1L,
                                        TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z",
                                                "100.000")));
                        if (outcome.status() == 200
                                && ((AcquisitionBatchResult) outcome.body()).committed() == 1) {
                            ok.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(other.get()).isZero();
        assertThat(ok.get()).isEqualTo(threads);
        // 只入账一次：1 包、1 批次、1 条力矩记录
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_packet", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_batch", Integer.class)).isEqualTo(1);
        assertThat(operationService.trace("CARD-CA3").torqueCurve()).hasSize(1);
        assertThat(acquisitionService.getSession("SES-CA3").version()).isEqualTo(2);
    }

    @Test
    @DisplayName("同 requestId 异参 409；同参重放冻结首次结果")
    void idempotencyReplayAndConflict() {
        prepareSession("CARD-CA4", "S-CA4", "SES-CA4");
        var batch = TestRequests.batch("b-ca4-1", 1L,
                TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "100.000"));
        Outcome first = acquisitionService.uploadBatch("SES-CA4", batch);
        assertThat(first.status()).isEqualTo(200);

        Outcome replay = acquisitionService.uploadBatch("SES-CA4", batch);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.replay()).isTrue();
        assertThat(acquisitionService.getSession("SES-CA4").version()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_packet", Integer.class)).isEqualTo(1);

        // 同键异参：409，状态不变
        Outcome conflict = acquisitionService.uploadBatch("SES-CA4", TestRequests.batch(
                "b-ca4-1", 1L, TestRequests.packet(1, "P1", "2026-09-26T08:00:01Z", "101.000")));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(((java.util.Map<?, ?>) conflict.body()).get("errorCode"))
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM acquisition_packet", Integer.class)).isEqualTo(1);
    }
}
