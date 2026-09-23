package com.example.starter.ctlog;

import com.example.starter.ctlog.domain.SplitAlert;
import com.example.starter.ctlog.dto.AddLeafRequest;
import com.example.starter.ctlog.dto.ObserveRequest;
import com.example.starter.ctlog.dto.ObserveResponse;
import com.example.starter.ctlog.dto.TreeHeadResponse;
import com.example.starter.ctlog.service.CtLogService;
import com.example.starter.ctlog.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观察者上报：验签、乱序树头、分裂视图告警与去重的 H2 集成测试。
 */
class ObserverServiceTest extends AbstractCtLogIntegrationTest {

    private static final String OBSERVER = "observer-1";
    private static final long TIMESTAMP = 1_700_000_000_000L;

    @Autowired
    private CtLogService service;

    @BeforeEach
    void seedLog() {
        for (int i = 0; i < 5; i++) {
            service.addLeaf(new AddLeafRequest("obs-leaf-" + i,
                    Base64.getEncoder().encodeToString(TestSigning.syntheticDer(i))));
        }
    }

    private ObserveRequest signedReport(String observerId, long treeSize, String rootHash, long timestamp) {
        String normalized = rootHash.toLowerCase();
        byte[] message = CtLogService.canonicalObserveMessage(observerId, treeSize, normalized, timestamp);
        return new ObserveRequest(observerId, treeSize, normalized, timestamp,
                TestSigning.signWithDemoKey(message));
    }

    private String logRootAt(long treeSize) {
        return service.getTreeHead(treeSize).rootHash();
    }

    private static String fakeRoot(int seed) {
        byte[] hash = com.example.starter.ctlog.merkle.MerkleTree.leafHash(new byte[]{(byte) seed});
        return java.util.HexFormat.of().formatHex(hash);
    }

    @Test
    void validMatchingReportIsAcceptedWithoutAlert() {
        ObserveResponse response = service.observe(
                signedReport(OBSERVER, 5, logRootAt(5), TIMESTAMP));
        assertTrue(response.accepted());
        assertFalse(response.alertCreated());
        assertEquals(5L, response.latestTreeSize());
        assertTrue(service.findAlerts().isEmpty());
    }

    @Test
    void invalidSignatureIsRejected() {
        ObserveRequest request = signedReport(OBSERVER, 5, logRootAt(5), TIMESTAMP);
        ObserveRequest tampered = new ObserveRequest(request.observerId(), request.treeSize(),
                request.rootHash(), request.timestamp() + 1, request.signature());
        ApiException ex = assertThrows(ApiException.class, () -> service.observe(tampered));
        assertEquals("INVALID_SIGNATURE", ex.getCode());
        assertTrue(service.findAlerts().isEmpty());
    }

    @Test
    void divergentRootCreatesSingleSplitViewAlertAndDeduplicates() {
        String fakeRoot = fakeRoot(9);
        ObserveResponse first = service.observe(signedReport(OBSERVER, 5, fakeRoot, TIMESTAMP));
        assertTrue(first.accepted());
        assertTrue(first.alertCreated());

        List<SplitAlert> alerts = service.findAlerts();
        assertEquals(1, alerts.size());
        SplitAlert alert = alerts.get(0);
        assertEquals("SPLIT_VIEW", alert.alertType());
        assertEquals(OBSERVER, alert.observerId());
        assertEquals(5L, alert.treeSize());
        assertEquals(logRootAt(5), alert.logRootHash());
        assertEquals(fakeRoot, alert.observerRootHash());
        assertTrue(alert.evidence().contains(fakeRoot));
        assertTrue(alert.evidence().contains(logRootAt(5)));

        // 重复上报同一分歧树头：不重复告警。
        ObserveResponse replay = service.observe(signedReport(OBSERVER, 5, fakeRoot, TIMESTAMP + 1));
        assertTrue(replay.accepted());
        assertFalse(replay.alertCreated());
        assertEquals(1, service.findAlerts().size());

        // 同一 treeSize 的另一种分歧根：仍由唯一约束去重，不产生第二条告警。
        ObserveResponse other = service.observe(signedReport(OBSERVER, 5, fakeRoot(10), TIMESTAMP + 2));
        assertFalse(other.alertCreated());
        assertEquals(1, service.findAlerts().size());
    }

    @Test
    void smallerTreeHeadMustNotRegressObserverPosition() {
        service.observe(signedReport(OBSERVER, 5, logRootAt(5), TIMESTAMP));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.observe(signedReport(OBSERVER, 3, logRootAt(3), TIMESTAMP + 1)));
        assertEquals("OBSERVER_HEAD_REGRESSION", ex.getCode());
        assertEquals(409, ex.getHttpStatus().value());

        // 位置未被倒退：之后等于当前位置的合法上报仍被接受。
        ObserveResponse after = service.observe(signedReport(OBSERVER, 5, logRootAt(5), TIMESTAMP + 2));
        assertTrue(after.accepted());
        assertEquals(5L, after.latestTreeSize());
    }

    @Test
    void reportBeyondCurrentLogSizeIsRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.observe(signedReport(OBSERVER, 6, logRootAt(5), TIMESTAMP)));
        assertEquals("TREE_SIZE_OUT_OF_RANGE", ex.getCode());
    }

    @Test
    void zeroTreeSizeReportIsRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.observe(signedReport(OBSERVER, 0, logRootAt(1), TIMESTAMP)));
        assertEquals("INVALID_TREE_SIZE", ex.getCode());
    }

    @Test
    void alertIsUniquePerTreeSizeAcrossObserversAndKeepsBothSidesEvidence() {
        // 首个观察者在 treeSize=4 上报分歧根：创建唯一告警，保留日志根与观察者根双方证据。
        ObserveResponse first = service.observe(signedReport("obs-a", 4, fakeRoot(1), TIMESTAMP));
        assertTrue(first.alertCreated());
        List<SplitAlert> afterFirst = service.findAlerts();
        assertEquals(1, afterFirst.size());
        assertEquals("obs-a", afterFirst.get(0).observerId());
        assertEquals(logRootAt(4), afterFirst.get(0).logRootHash());
        assertEquals(fakeRoot(1), afterFirst.get(0).observerRootHash());

        // 不同观察者在同一 treeSize 上报另一种分歧根：不重复告警（按日志+treeSize 去重）。
        ObserveResponse second = service.observe(signedReport("obs-b", 4, fakeRoot(2), TIMESTAMP + 1));
        assertFalse(second.alertCreated());
        assertEquals(1, service.findAlerts().size());

        // 同一观察者在更大 treeSize 上报分歧根：产生第二条告警。
        ObserveResponse third = service.observe(signedReport("obs-a", 5, fakeRoot(3), TIMESTAMP + 2));
        assertTrue(third.alertCreated());
        List<SplitAlert> alerts = service.findAlerts();
        assertEquals(2, alerts.size());
        assertEquals(4L, alerts.get(0).treeSize());
        assertEquals(5L, alerts.get(1).treeSize());
        assertEquals(logRootAt(5), alerts.get(1).logRootHash());
    }

    @Test
    void concurrentAppendsAndReportsStayOnCommittedPrefixes() throws Exception {
        int threads = 8;
        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();

        // 追加线程：并发将树从 5 扩展到 5 + threads*rounds。
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    for (int r = 0; r < rounds; r++) {
                        service.addLeaf(new AddLeafRequest("rw-leaf-" + threadId + "-" + r,
                                Base64.getEncoder().encodeToString(
                                        TestSigning.syntheticDer(100 + threadId * rounds + r))));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // 上报线程：每个观察者始终上报“当前已提交树大小”的正确根，大小单调不减。
        for (int t = 0; t < threads; t++) {
            final String observerId = "rw-observer-" + t;
            futures.add(pool.submit(() -> {
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    long last = 0;
                    for (int r = 0; r < rounds; r++) {
                        TreeHeadResponse head = service.getTreeHead(null);
                        assertTrue(head.treeSize() >= last, "read tree size regressed");
                        ObserveResponse response = service.observe(signedReport(
                                observerId, head.treeSize(), head.rootHash(), TIMESTAMP + r));
                        assertTrue(response.accepted());
                        assertFalse(response.alertCreated());
                        assertTrue(response.latestTreeSize() >= last);
                        last = response.latestTreeSize();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(90, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(5L + (long) threads * rounds, service.getTreeHead(null).treeSize());
        // 全部为正确根上报，不应产生任何分裂视图告警。
        assertTrue(service.findAlerts().isEmpty());
    }

    @Test
    void concurrentDivergentReportsCreateExactlyOneAlert() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ObserveResponse>> futures = new java.util.ArrayList<>();
        String divergent = fakeRoot(77);
        for (int t = 0; t < threads; t++) {
            final int offset = t;
            futures.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.observe(signedReport("race-observer-" + offset, 3, divergent, TIMESTAMP));
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        for (Future<ObserveResponse> future : futures) {
            if (future.get(30, TimeUnit.SECONDS).alertCreated()) {
                created.incrementAndGet();
            }
        }
        pool.shutdownNow();
        assertEquals(1, created.get());
        assertEquals(1, service.findAlerts().size());
    }
}
