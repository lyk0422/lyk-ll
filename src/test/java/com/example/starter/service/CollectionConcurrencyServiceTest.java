package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.CollectionUploadRequest;
import com.example.starter.api.dto.CollectionUploadResult;
import com.example.starter.support.ApiException;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 采集上传并发边界：相邻包并发上传不得重复消耗前缀或多算返工；
 * 同 requestId 并发重放只入账一次。
 */
class CollectionConcurrencyServiceTest extends AbstractServiceTest {

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private OperationService operationService;

    private void prepareSession(String sessionNo, String cardNo, String station, boolean twoPoints) {
        operationService.registerTool(TestRequests.tool("TW-1", TestRequests.TODAY));
        if (twoPoints) {
            operationService.dispatch(TestRequests.dispatch("d-" + cardNo, cardNo, station, "N"));
        } else {
            operationService.dispatch(
                    TestRequests.singlePoint("d-" + cardNo, cardNo, station, "N", "P1", "100.000"));
        }
        collectionService.createSession(TestRequests.createSession("cs-" + sessionNo, sessionNo, cardNo, "TW-1"));
    }

    /** 带版本重试的上传：409（版本/并发冲突）时读取最新版本重试，直至成功或超出次数。 */
    private CollectionUploadResult uploadWithRetry(String requestId, String sessionNo,
                                                   CollectionUploadRequest.PacketSpec spec) {
        for (int attempt = 0; attempt < 10; attempt++) {
            int version = collectionService.getSession(sessionNo).version();
            try {
                Outcome outcome = collectionService.upload(
                        TestRequests.upload(requestId + "-" + attempt, sessionNo, version, spec));
                if (outcome.status() == 200) {
                    return (CollectionUploadResult) outcome.body();
                }
                assertThat(outcome.status()).isEqualTo(409);
            } catch (ApiException e) {
                assertThat(e.getStatus()).isEqualTo(409);
            }
        }
        throw new IllegalStateException("并发重试未收敛: " + requestId);
    }

    @Test
    @DisplayName("8 线程并发上传相邻包（序号 1～8）：前缀不重复消费，每包恰入账一次，版本与成功变更数一致")
    void concurrentAdjacentPacketsNoDoubleConsumption() throws InterruptedException {
        prepareSession("SES-CC1", "CARD-CC1", "S-CC1", true);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<CollectionUploadResult> results = new CopyOnWriteArrayList<>();
        AtomicInteger failures = new AtomicInteger();

        try {
            for (int i = 1; i <= threads; i++) {
                final int seq = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        String point = seq % 2 == 1 ? "P1" : "P2";
                        String torque = seq % 2 == 1 ? "100.000" : "50.000";
                        results.add(uploadWithRetry("u-cc-" + seq, "SES-CC1",
                                TestRequests.packet(seq, point, torque,
                                        "2026-09-26T01:00:" + String.format("%02d", seq) + "Z")));
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures.get()).isZero();
        assertThat(results).hasSize(threads);

        // 最终状态：水位线 8，每包恰一条力矩记录，无返工，无重复消费
        assertThat(collectionService.getSession("SES-CC1").watermark()).isEqualTo(threads);
        assertThat(collectionService.listPackets("SES-CC1")).hasSize(threads);
        assertThat(collectionService.getSession("SES-CC1").consumedCount()).isEqualTo(threads);
        assertThat(operationService.trace("CARD-CC1").torqueCurve()).hasSize(threads);
        assertThat(operationService.trace("CARD-CC1").reworkLedger()).isEmpty();
        // 版本 = 1 + 成功变更次数（每次成功上传加 1）
        assertThat(collectionService.getSession("SES-CC1").version()).isEqualTo(1 + threads);
        // 力矩链每个紧固点 attempt_no 连续无空洞（无重复消耗）
        Integer maxAttemptP1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torque_record tr JOIN fastener f ON tr.fastener_id = f.id "
                        + "WHERE f.point_code = 'P1'", Integer.class);
        assertThat(maxAttemptP1).isEqualTo(4);
    }

    @Test
    @DisplayName("并发同点超差包：返工台账不多算，每条超差力矩恰对应一次返工")
    void concurrentOutOfToleranceNoDoubleRework() throws InterruptedException {
        prepareSession("SES-CC2", "CARD-CC2", "S-CC2", false);
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        try {
            for (int i = 1; i <= threads; i++) {
                final int seq = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        uploadWithRetry("u-oot-" + seq, "SES-CC2",
                                TestRequests.packet(seq, "P1", "200.000", "2026-09-26T01:00:0" + seq + "Z"));
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures.get()).isZero();
        // 2 条超差力矩记录、恰 2 条返工（rework_no 1、2 各一次，不多算）
        assertThat(operationService.trace("CARD-CC2").torqueCurve()).hasSize(2);
        assertThat(operationService.trace("CARD-CC2").reworkLedger()).hasSize(2);
        assertThat(operationService.trace("CARD-CC2").reworkLedger())
                .extracting("reworkNo").containsExactlyInAnyOrder(1, 2);
        assertThat(collectionService.getSession("SES-CC2").watermark()).isEqualTo(2);
    }

    @Test
    @DisplayName("同 requestId 同参并发上传：只入账一次，两个调用都获成功结果")
    void concurrentSameRequestIdSinglePosting() throws InterruptedException {
        prepareSession("SES-CC3", "CARD-CC3", "S-CC3", false);
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
                        Outcome outcome = collectionService.upload(TestRequests.upload(
                                "u-same-key", "SES-CC3", 1,
                                TestRequests.packet(1, "P1", "100.000", "2026-09-26T01:00:00Z")));
                        if (outcome.status() == 200) {
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
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(other.get()).isZero();
        assertThat(ok.get()).isEqualTo(threads);
        // 只入账一次：1 包、1 条力矩、水位线 1、版本 2
        assertThat(collectionService.listPackets("SES-CC3")).hasSize(1);
        assertThat(operationService.trace("CARD-CC3").torqueCurve()).hasSize(1);
        assertThat(collectionService.getSession("SES-CC3").watermark()).isEqualTo(1);
        assertThat(collectionService.getSession("SES-CC3").version()).isEqualTo(2);
    }
}
