package com.example.starter.signing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.signing.Contracts.CancelPackageRequest;
import com.example.starter.signing.Contracts.CreatePackageRequest;
import com.example.starter.signing.Contracts.PackageState;
import com.example.starter.signing.Contracts.SignatureResult;
import com.example.starter.signing.Contracts.Step;
import com.example.starter.signing.Contracts.SubmitSignatureRequest;

/**
 * 并发与竞态测试：取消/到期与签名竞争形成一致终态、同键并发请求幂等、
 * 并行签署人同时提交只完成一次。真实并发线程 + 超时控制，断言最终数据。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class SigningConcurrencyTest {

    private static final String DOC_V1 = Hashing.sha256Hex("doc-v1");

    @Autowired
    private SigningService service;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM signing_event");
        jdbc.update("DELETE FROM signature");
        jdbc.update("DELETE FROM package_step");
        jdbc.update("DELETE FROM signing_package");
        clock.set(Instant.parse("2026-09-23T00:00:00Z"));
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private PackageState createPackage(String packageId, List<Step> steps) {
        return service.createPackage(new CreatePackageRequest(
                packageId, DOC_V1, Instant.parse("2026-09-23T01:00:00Z"), steps));
    }

    private SubmitSignatureRequest signRequest(PackageState state, String signer, String requestId) {
        return new SubmitSignatureRequest(requestId, signer, state.currentRevision(),
                state.docSha256(), state.version(),
                SyntheticSignatures.sign(state.packageId(), state.currentRevision(), signer, state.docSha256()));
    }

    /** 两个任务在同一栅栏后同时执行，返回各自结果（异常包装为结果对象）。 */
    private record Outcome(Object value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }

    private List<Outcome> race(Runnable taskA, Runnable taskB) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Outcome> futureA = executor.submit(() -> runTagged(taskA, ready, start));
        Future<Outcome> futureB = executor.submit(() -> runTagged(taskB, ready, start));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        Outcome a = futureA.get(30, TimeUnit.SECONDS);
        Outcome b = futureB.get(30, TimeUnit.SECONDS);
        return List.of(a, b);
    }

    private Outcome runTagged(Runnable task, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            task.run();
            return new Outcome(null, null);
        } catch (Throwable t) {
            return new Outcome(null, t);
        }
    }

    @Test
    void cancelAndFinalSignatureRaceToConsistentTerminalState() throws Exception {
        PackageState state = createPackage(UUID.randomUUID().toString(),
                List.of(new Step(List.of("alice"))));
        SubmitSignatureRequest finalSignature = signRequest(state, "alice", "req-race-cancel");
        CancelPackageRequest cancel = new CancelPackageRequest(state.version());

        List<Outcome> outcomes = race(
                () -> service.submitSignature(state.packageId(), finalSignature),
                () -> service.cancel(state.packageId(), cancel));

        long successes = outcomes.stream().filter(Outcome::succeeded).count();
        assertThat(successes).isEqualTo(1);
        outcomes.stream().filter(o -> !o.succeeded()).forEach(o ->
                assertThat(o.error()).isInstanceOf(ApiException.class));

        PackageState terminal = service.getState(state.packageId());
        Integer signatures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature WHERE package_id = ?", Integer.class, state.packageId());
        if ("COMPLETED".equals(terminal.status())) {
            // 签名胜出：证据包已原子冻结，取消方失败。
            assertThat(signatures).isEqualTo(1);
            assertThat(terminal.evidenceFrozen()).isTrue();
            assertThat(service.getEvidence(state.packageId()).validSignatures()).hasSize(1);
        } else {
            // 取消胜出：迟到签名不得复活流程，无证据包。
            assertThat(terminal.status()).isEqualTo("CANCELLED");
            assertThat(signatures).isZero();
            assertThat(terminal.evidenceFrozen()).isFalse();
        }
    }

    @Test
    void concurrentIdenticalRequestKeyYieldsSingleSignature() throws Exception {
        PackageState state = createPackage(UUID.randomUUID().toString(),
                List.of(new Step(List.of("alice", "bob"))));
        SubmitSignatureRequest request = signRequest(state, "alice", "req-race-same-key");

        List<Outcome> outcomes = race(
                () -> service.submitSignature(state.packageId(), request),
                () -> service.submitSignature(state.packageId(), request));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.succeeded()).isTrue());
        Integer signatures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature WHERE package_id = ?", Integer.class, state.packageId());
        Integer submittedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signing_event WHERE package_id = ? AND event_type = 'SIGNATURE_SUBMITTED'",
                Integer.class, state.packageId());
        assertThat(signatures).isEqualTo(1);
        assertThat(submittedEvents).isEqualTo(1);
    }

    @Test
    void concurrentParallelSignersCompletePackageExactlyOnce() throws Exception {
        PackageState state = createPackage(UUID.randomUUID().toString(),
                List.of(new Step(List.of("alice", "bob"))));
        // 两人基于同一版本并发提交：行锁串行后后者读到新版本，需各自取最新版本。
        List<Outcome> outcomes = race(
                () -> submitWithFreshVersion(state.packageId(), "alice", "req-race-alice"),
                () -> submitWithFreshVersion(state.packageId(), "bob", "req-race-bob"));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.succeeded())
                .as("both parallel signers should succeed, got: %s", o.error()).isTrue());
        PackageState terminal = service.getState(state.packageId());
        assertThat(terminal.status()).isEqualTo("COMPLETED");
        assertThat(terminal.evidenceFrozen()).isTrue();
        Integer completedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signing_event WHERE package_id = ? AND event_type = 'PACKAGE_COMPLETED'",
                Integer.class, state.packageId());
        Integer frozenEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signing_event WHERE package_id = ? AND event_type = 'EVIDENCE_FROZEN'",
                Integer.class, state.packageId());
        assertThat(completedEvents).isEqualTo(1);
        assertThat(frozenEvents).isEqualTo(1);
        assertThat(EventChain.verify(state.packageId(),
                service.getEvidence(state.packageId()).events().stream()
                        .map(e -> new SigningRepository.EventRow(e.seq(), e.eventType(), e.payload(),
                                e.prevHash(), e.eventHash()))
                        .toList())).isTrue();
    }

    @Test
    void expiryAndSignatureRaceToConsistentTerminalState() throws Exception {
        PackageState state = createPackage(UUID.randomUUID().toString(),
                List.of(new Step(List.of("alice"))));
        SubmitSignatureRequest request = signRequest(state, "alice", "req-race-expiry");

        List<Outcome> outcomes = race(
                () -> service.submitSignature(state.packageId(), request),
                () -> clock.advance(Duration.ofDays(2)));

        // 时钟推进必然成功；签名可能成功（先于到期检查）或被到期拒绝。
        PackageState terminal = service.getState(state.packageId());
        Integer signatures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature WHERE package_id = ?", Integer.class, state.packageId());
        boolean signatureWon = outcomes.get(0).succeeded();
        if (signatureWon) {
            assertThat(signatures).isEqualTo(1);
            assertThat(terminal.status()).isIn("ACTIVE", "COMPLETED", "EXPIRED");
        } else {
            assertThat(outcomes.get(0).error()).isInstanceOf(ApiException.class);
            assertThat(signatures).isZero();
        }
        // 终态一致：到期后绝不再接受新签名（迟到签名不得复活流程）。
        if ("EXPIRED".equals(terminal.status())) {
            assertThat(signatures).isZero();
        }
    }

    private void submitWithFreshVersion(String packageId, String signer, String requestId) {
        // 并发下版本号可能被对方提交推进：读取最新版本重试，直到签名被受理。
        for (int attempt = 0; attempt < 20; attempt++) {
            PackageState fresh = service.getState(packageId);
            try {
                service.submitSignature(packageId, signRequest(fresh, signer, requestId));
                return;
            } catch (ApiException e) {
                if (!"VERSION_CONFLICT".equals(e.code())) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("signature not accepted after retries");
    }
}
