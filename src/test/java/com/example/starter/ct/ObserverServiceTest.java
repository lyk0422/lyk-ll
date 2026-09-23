package com.example.starter.ct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 观察者上报与分裂视图告警测试：验签、乱序不倒退、告警原子去重、固定时钟。
 */
@SpringBootTest
class ObserverServiceTest {

    private static final long FIXED_NOW = 1760000000000L;
    private static final HexFormat HEX = HexFormat.of();

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.ofEpochMilli(FIXED_NOW), ZoneOffset.UTC);
        }
    }

    @Autowired
    private ObserverService observerService;

    @Autowired
    private CtLogService ctLogService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM split_view_alert");
        jdbc.update("DELETE FROM observer_report");
        jdbc.update("DELETE FROM observer_state");
        jdbc.update("DELETE FROM ct_leaf");
    }

    private void appendLeaves(int count) {
        for (int i = 0; i < count; i++) {
            ctLogService.append("req-" + i, ("cert-" + i).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String fakeRoot(String seed) throws Exception {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validReportMatchingLocalRootIsAcceptedWithoutAlert() {
        appendLeaves(4);
        String root = ctLogService.treeHead(4L).rootHash();
        var result = observerService.report("obs-1", 4, root, FIXED_NOW - 1000,
                TestKeys.sign("obs-1", 4, root, FIXED_NOW - 1000));
        assertTrue(result.accepted());
        assertFalse(result.alertCreated());
        assertNull(result.alertId());
        assertEquals(4, result.observerPosition());
        assertTrue(observerService.alerts().isEmpty());
    }

    @Test
    void invalidSignatureIsRejectedAndNothingPersisted() throws Exception {
        appendLeaves(2);
        String root = ctLogService.treeHead(2L).rootHash();

        // 用另一把私钥签名。
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        var otherKeyPair = generator.generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(otherKeyPair.getPrivate());
        signer.update("obs-1|2|".concat(root).concat("|5").getBytes(StandardCharsets.UTF_8));
        String foreignSignature = Base64.getEncoder().encodeToString(signer.sign());

        CtException ex = assertThrows(CtException.class,
                () -> observerService.report("obs-1", 2, root, 5, foreignSignature));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.status());
        assertEquals("SIGNATURE_INVALID", ex.code());

        // 篡改的签名同样拒绝。
        assertThrows(CtException.class,
                () -> observerService.report("obs-1", 2, root, 5, TestKeys.sign("obs-1", 2, root, 6)));
        assertThrows(CtException.class,
                () -> observerService.report("obs-1", 2, root, 5, "not-a-signature"));

        assertTrue(observerService.alerts().isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM observer_state", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM observer_report", Long.class));
    }

    @Test
    void malformedRootHashAndZeroTreeSizeAreRejected() {
        appendLeaves(2);
        assertEquals("INVALID_ROOT_HASH", assertThrows(CtException.class,
                () -> observerService.report("obs-1", 2, "xyz", 5, "sig")).code());
        assertEquals("INVALID_ROOT_HASH", assertThrows(CtException.class,
                () -> observerService.report("obs-1", 2, "abcd", 5, "sig")).code());
        assertEquals("INVALID_TREE_SIZE", assertThrows(CtException.class,
                () -> observerService.report("obs-1", 0, "00", 5, "sig")).code());
    }

    @Test
    void smallerTreeHeadDoesNotRegressObserverPosition() {
        appendLeaves(10);
        String root8 = ctLogService.treeHead(8L).rootHash();
        var first = observerService.report("obs-1", 8, root8, 100, TestKeys.sign("obs-1", 8, root8, 100));
        assertEquals(8, first.observerPosition());

        String root3 = ctLogService.treeHead(3L).rootHash();
        var second = observerService.report("obs-1", 3, root3, 200, TestKeys.sign("obs-1", 3, root3, 200));
        assertTrue(second.accepted());
        assertFalse(second.alertCreated());
        assertEquals(8, second.observerPosition(), "较小树头不得倒退观察者位置");
    }

    @Test
    void splitViewAlertCreatedAtomicallyOnceWithBothEvidences() throws Exception {
        appendLeaves(6);
        String localRoot = ctLogService.treeHead(4L).rootHash();
        String fakeRoot = fakeRoot("evil-root-4");

        var first = observerService.report("obs-1", 4, fakeRoot, 1000,
                TestKeys.sign("obs-1", 4, fakeRoot, 1000));
        assertTrue(first.alertCreated());
        assertNotNull(first.alertId());

        // 重复上报不重复告警。
        var duplicate = observerService.report("obs-1", 4, fakeRoot, 1000,
                TestKeys.sign("obs-1", 4, fakeRoot, 1000));
        assertFalse(duplicate.alertCreated());

        // 另一观察者对同一 treeSize 上报不同根也不重复告警。
        String fakeRoot2 = fakeRoot("evil-root-4b");
        var secondObserver = observerService.report("obs-2", 4, fakeRoot2, 1001,
                TestKeys.sign("obs-2", 4, fakeRoot2, 1001));
        assertFalse(secondObserver.alertCreated());

        var alerts = observerService.alerts();
        assertEquals(1, alerts.size());
        var alert = alerts.get(0);
        assertEquals("SPLIT_VIEW", alert.alertType());
        assertEquals("obs-1", alert.observerId());
        assertEquals(4, alert.treeSize());
        assertEquals(localRoot, alert.localRootHash());
        assertEquals(fakeRoot, alert.reportedRootHash());
        assertEquals(1000, alert.reportedAt());
        assertEquals(FIXED_NOW, alert.createdAt(), "告警创建时间应来自可控时钟");
    }

    @Test
    void conflictAtDifferentTreeSizeCreatesSeparateAlert() throws Exception {
        appendLeaves(6);
        String fake4 = fakeRoot("evil-4");
        String fake6 = fakeRoot("evil-6");
        observerService.report("obs-1", 4, fake4, 1, TestKeys.sign("obs-1", 4, fake4, 1));
        var second = observerService.report("obs-1", 6, fake6, 2, TestKeys.sign("obs-1", 6, fake6, 2));
        assertTrue(second.alertCreated());
        assertEquals(2, observerService.alerts().size());
    }

    @Test
    void concurrentConflictingReportsCreateExactlyOneAlert() throws Exception {
        appendLeaves(5);
        String fakeRoot = fakeRoot("evil-concurrent");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String observerId = "obs-c" + t;
            futures.add(pool.submit(() -> {
                gate.await();
                return observerService.report(observerId, 5, fakeRoot, 500,
                        TestKeys.sign(observerId, 5, fakeRoot, 500)).alertCreated();
            }));
        }
        gate.countDown();
        int created = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(60, TimeUnit.SECONDS)) {
                created++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, created, "并发下同一 treeSize 只能创建一个告警");
        assertEquals(1, observerService.alerts().size());
    }

    @Test
    void reportAheadOfLocalLogIsAcceptedWithoutComparison() throws Exception {
        appendLeaves(2);
        String futureRoot = fakeRoot("future-root");
        var result = observerService.report("obs-9", 10, futureRoot, 700,
                TestKeys.sign("obs-9", 10, futureRoot, 700));
        assertTrue(result.accepted());
        assertFalse(result.alertCreated());
        assertEquals(10, result.observerPosition());
        assertTrue(observerService.alerts().isEmpty());
    }
}
