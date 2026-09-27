package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.CollectionBatchResult;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 采集并发测试（真实线程 + H2 行锁）：
 * 相邻包并发上传不重复消耗前缀、不产生重复力矩 attempt；
 * 同 requestId 并发只落首次冻结结果；超差链在并发下也只计恰 2 次返工。
 * 调用方在 expectedVersion 冲突（409）后重读版本并重试，模拟设备本地 CAS 提交。
 */
class ConcurrentCollectionServiceTest extends AbstractServiceTest {

    private static final String CARD = "CARD-CC";
    private static final String STATION = "ST-CC";
    private static final String TOOL = "T1";
    private static final String SESSION = "SESS-CC";

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private OperationService operationService;

    private void open(int expectedCount) {
        operationService.registerTool(TestRequests.tool(TOOL, TestRequests.TODAY));
        operationService.dispatch(TestRequests.singlePoint(
                "d-cc", CARD, STATION, "N", "P1", "100.000"));
        collectionService.openSession(TestRequests.session(
                "open-cc", SESSION, CARD, TOOL, expectedCount, List.of("P1")));
    }

    /** CAS 提交：版本冲突则重读版本重试；返回最终结果。 */
    private Outcome submitWithRetry(String requestId, int first, int second, String torque) {
        int expectedVersion = 1;
        Outcome outcome = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            outcome = collectionService.collect(TestRequests.batch(requestId, SESSION, expectedVersion,
                    List.of(TestRequests.packet(first, "P1", torque),
                            TestRequests.packet(second, "P1", torque))));
            if (outcome.status() == 200) {
                return outcome;
            }
            if (outcome.status() != 409
                    || !"VERSION_CONFLICT".equals(((Map<?, ?>) outcome.body()).get("errorCode"))) {
                return outcome;
            }
            expectedVersion = collectionService.getSession(SESSION).version();
        }
        return outcome;
    }

    @Test
    @DisplayName("4 线程并发上传相邻包(1-8)，版本冲突重试后：水位线 8、力矩 attempt 1-8 无重复")
    void concurrentAdjacentPacketsDrainOnce() throws InterruptedException {
        open(8);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                final int first = t * 2 + 1;
                final int second = first + 1;
                final String requestId = "cc-batch-" + t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        Outcome outcome = submitWithRetry(requestId, first, second, "100");
                        if (outcome.status() == 200) {
                            ok.incrementAndGet();
                        } else {
                            unexpected.incrementAndGet();
                        }
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(unexpected.get()).isZero();
        assertThat(ok.get()).isEqualTo(threads);
        assertThat(collectionService.getWatermark(SESSION).watermark()).isEqualTo(8);
        assertThat(collectionService.getWatermark(SESSION).complete()).isTrue();

        Integer torqueRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torque_record WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = ?)", Integer.class, CARD);
        assertThat(torqueRecords).isEqualTo(8);
        Integer distinctAttempts = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT attempt_no) FROM torque_record WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = ?)", Integer.class, CARD);
        assertThat(distinctAttempts).isEqualTo(8);
        Integer admitted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collection_packet WHERE state = 'ADMITTED' AND session_id = "
                        + "(SELECT id FROM collection_session WHERE session_no = ?)", Integer.class, SESSION);
        assertThat(admitted).isEqualTo(8);
    }

    @Test
    @DisplayName("同 requestId 同参 4 线程并发：全部返回首次冻结结果，只产生 2 个包与 2 条力矩记录")
    void concurrentSameRequestIdFreezesFirstResult() throws InterruptedException {
        open(4);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        Outcome outcome = collectionService.collect(TestRequests.batch(
                                "cc-same-key", SESSION, 1, List.of(
                                        TestRequests.packet(1, "P1", "100"),
                                        TestRequests.packet(2, "P1", "100"))));
                        if (outcome.status() == 200 && outcome.replay()) {
                            replayed.incrementAndGet();
                        } else if (outcome.status() == 200) {
                            success.incrementAndGet();
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
        assertThat(success.get() + replayed.get()).isEqualTo(threads);
        assertThat(success.get()).isEqualTo(1);
        assertThat(collectionService.getWatermark(SESSION).watermark()).isEqualTo(2);
        Integer torqueRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torque_record WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = ?)", Integer.class, CARD);
        assertThat(torqueRecords).isEqualTo(2);
        Integer ledgerRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'cc-same-key'",
                Integer.class);
        assertThat(ledgerRows).isEqualTo(1);
    }

    @Test
    @DisplayName("连续 3 次超差：恰记 2 次返工后整批隔离，后续序号保持缓冲不再入账")
    void quarantineStopsPrefixDrainWithExactlyTwoReworks() {
        open(5);
        // 标称 100、±5%；120 恒超差。本批 1..5：1/2 返工，第 3 次仍超差触发隔离，4/5 缓冲。
        Outcome outcome = collectionService.collect(TestRequests.batch(
                "cc-fail", SESSION, 1, List.of(
                        TestRequests.packet(1, "P1", "120"),
                        TestRequests.packet(2, "P1", "120"),
                        TestRequests.packet(3, "P1", "120"),
                        TestRequests.packet(4, "P1", "120"),
                        TestRequests.packet(5, "P1", "120"))));
        assertThat(outcome.status()).isEqualTo(200);
        CollectionBatchResult result = (CollectionBatchResult) outcome.body();
        assertThat(result.quarantined()).isTrue();
        assertThat(result.quarantineSeq()).isEqualTo(3);
        assertThat(result.watermarkAfter()).isEqualTo(3);
        assertThat(result.bufferedSeqs()).containsExactly(4, 5);

        Integer reworks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rework_record WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = ?)", Integer.class, CARD);
        assertThat(reworks).isEqualTo(2);
        Integer torqueRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM torque_record WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = ?)", Integer.class, CARD);
        assertThat(torqueRecords).isEqualTo(3);
        assertThat(operationService.getCard(CARD).status()).isEqualTo("QUARANTINED");

        Outcome frozen = collectionService.collect(TestRequests.batch(
                "cc-frozen", SESSION, 2, List.of(TestRequests.packet(4, "P1", "100"))));
        assertThat(frozen.status()).isEqualTo(422);
        assertThat(((Map<?, ?>) frozen.body()).get("errorCode")).isEqualTo("CARD_QUARANTINED");
    }
}
