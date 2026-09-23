package com.example.starter.signing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.signing.service.Hashes;
import com.example.starter.signing.service.SigningService;
import com.example.starter.signing.support.AbstractSigningTest;
import com.example.starter.signing.web.ActionResponse;
import com.example.starter.signing.web.CancelRequest;
import com.example.starter.signing.web.CreatePackageRequest;
import com.example.starter.signing.web.EvidenceResponse;
import com.example.starter.signing.web.PackageStatusResponse;
import com.example.starter.signing.web.RevisionRequest;

/**
 * H2 事务与真实线程并发：并行签署、完成冻结、取消/修订竞态下的一致终态。
 */
class SigningConcurrencyTest extends AbstractSigningTest {

    /**
     * 同一步骤两个并行签署人并发提交：各自恰好成功一次，事件与签名数量精确。
     */
    @Test
    void parallelSignersSucceedExactlyOnce() throws Exception {
        String hash = docHash("par-step");
        String pkgId = createPackage(hash, EXPIRES, List.of(
                new CreatePackageRequest.StepSpec(1, List.of("alice", "bob")),
                new CreatePackageRequest.StepSpec(2, List.of("carol"))));

        runConcurrently(2, index -> {
            String signer = index == 0 ? "alice" : "bob";
            String requestId = "par-" + signer;
            return service.submitSignature(pkgId,
                    signRequest(requestId, signer, 1, hash, 1)).status();
        });

        Long valid = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature_record WHERE package_id = ? AND status = 'VALID'",
                Long.class, pkgId);
        assertThat(valid).isEqualTo(2L);
        Long signedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sign_event WHERE package_id = ? AND event_type = 'SIGNED'",
                Long.class, pkgId);
        assertThat(signedEvents).isEqualTo(2L);
        // 第二步尚未完成，流程仍在进行
        assertThat(service.getStatus(pkgId).status()).isEqualTo("PENDING");
        assertChainContinuousAndLinked(pkgId);
    }

    /**
     * 同一签署人用不同 requestId 并发重复提交：恰好一个成功，另一个拒绝。
     */
    @Test
    void concurrentDuplicateSubmissionYieldsSingleSignature() throws Exception {
        String hash = docHash("par-dup");
        String pkgId = createPackage(hash, EXPIRES, List.of(
                new CreatePackageRequest.StepSpec(1, List.of("alice")),
                new CreatePackageRequest.StepSpec(2, List.of("bob"))));

        List<HttpStatus> statuses = runConcurrently(2, index -> service.submitSignature(pkgId,
                signRequest("dup-" + index, "alice", 1, hash, 1)).status());

        long ok = statuses.stream().filter(s -> s == HttpStatus.OK).count();
        long conflict = statuses.stream()
                .filter(s -> s == HttpStatus.CONFLICT).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);

        Long signatures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature_record WHERE package_id = ?", Long.class, pkgId);
        assertThat(signatures).isEqualTo(1L);
        assertChainContinuousAndLinked(pkgId);
    }

    /**
     * 最后两个并行签名并发：恰好一次冻结证据包，状态 COMPLETED。
     */
    @Test
    void lastParallelSignaturesFreezeExactlyOnce() throws Exception {
        String hash = docHash("par-final");
        String pkgId = createPackage(hash, EXPIRES, List.of(
                new CreatePackageRequest.StepSpec(1, List.of("alice", "bob"))));

        List<HttpStatus> statuses = runConcurrently(2, index -> {
            String signer = index == 0 ? "alice" : "bob";
            return service.submitSignature(pkgId,
                    signRequest("fin-" + signer, signer, 1, hash, 1)).status();
        });
        assertThat(statuses).allSatisfy(s -> assertThat(s).isEqualTo(HttpStatus.OK));

        PackageStatusResponse status = service.getStatus(pkgId);
        assertThat(status.status()).isEqualTo("COMPLETED");

        Long evidenceRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_package WHERE package_id = ?", Long.class, pkgId);
        assertThat(evidenceRows).isEqualTo(1L);

        EvidenceResponse evidence = service.getEvidence(pkgId);
        assertThat(evidence.validSignatures()).hasSize(2);
        List<String> types = evidence.events().stream().map(EvidenceResponse.EventView::type).toList();
        assertThat(types).endsWith("COMPLETED");
        // COMPLETED 事件恰好一个
        long completedEvents = types.stream().filter("COMPLETED"::equals).count();
        assertThat(completedEvents).isEqualTo(1);
        assertChainContinuousAndLinked(pkgId);
    }

    /**
     * 最后签名与取消并发：终态唯一且一致——要么已冻结 COMPLETED，要么 CANCELLED 且无证据包。
     */
    @Test
    void signAndCancelRaceReachesSingleConsistentTerminalState() throws Exception {
        String hash = docHash("par-cancel");
        AtomicInteger inconsistency = new AtomicInteger();

        // 多次运行提高竞态覆盖
        for (int round = 0; round < 5; round++) {
            cleanTables();
            resetClock();
            final int roundTag = round;
            String pkgId = createPackage(hash, EXPIRES, List.of(
                    new CreatePackageRequest.StepSpec(1, List.of("alice"))));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<HttpStatus> signFuture = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.submitSignature(pkgId,
                            signRequest("race-sign-" + roundTag, "alice", 1, hash, 1)).status();
                });
                Future<ActionResponse> cancelFuture = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.cancel(pkgId, new CancelRequest("race"));
                });
                start.countDown();
                HttpStatus signStatus = signFuture.get(10, TimeUnit.SECONDS);
                ActionResponse cancelResponse;
                try {
                    cancelResponse = cancelFuture.get(10, TimeUnit.SECONDS);
                    assertThat(cancelResponse.accepted()).isTrue();
                } catch (Exception e) {
                    // 签名先完成时取消被业务拒绝，属预期之一
                    cancelResponse = null;
                }

                PackageStatusResponse status = service.getStatus(pkgId);
                Long evidenceRows = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM evidence_package WHERE package_id = ?",
                        Long.class, pkgId);

                if ("COMPLETED".equals(status.status())) {
                    assertThat(signStatus).isEqualTo(HttpStatus.OK);
                    assertThat(evidenceRows).isEqualTo(1L);
                } else if ("CANCELLED".equals(status.status())) {
                    assertThat(signStatus).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(evidenceRows).isZero();
                } else {
                    inconsistency.incrementAndGet();
                }
                assertChainContinuousAndLinked(pkgId);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(inconsistency.get()).isZero();
    }

    /**
     * 签名与修订并发：终态修订号与有效签名修订严格一致，无跨修订脏签名。
     */
    @Test
    void signAndRevisionRaceKeepsRevisionConsistency() throws Exception {
        String v1 = docHash("par-rev-v1");
        String v2 = docHash("par-rev-v2");

        for (int round = 0; round < 5; round++) {
            cleanTables();
            resetClock();
            final int roundTag = round;
            String pkgId = createPackage(v1, EXPIRES, List.of(
                    new CreatePackageRequest.StepSpec(1, List.of("alice")),
                    new CreatePackageRequest.StepSpec(2, List.of("bob"))));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<HttpStatus> signFuture = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.submitSignature(pkgId,
                            signRequest("revrace-s-" + roundTag, "alice", 1, v1, 1)).status();
                });
                Future<Integer> reviseFuture = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.revise(pkgId, new RevisionRequest(v2)).revision();
                });
                start.countDown();
                HttpStatus signStatus = signFuture.get(10, TimeUnit.SECONDS);
                int resultingRevision = reviseFuture.get(10, TimeUnit.SECONDS);

                PackageStatusResponse status = service.getStatus(pkgId);
                assertThat(status.currentRevision()).isEqualTo(resultingRevision);
                assertThat(status.status()).isEqualTo("PENDING");

                // 有效签名的修订号必须等于当前修订号；旧修订签名若存在必须为 INVALIDATED
                Long validOnCurrent = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM signature_record WHERE package_id = ? "
                                + "AND status = 'VALID' AND revision = ?",
                        Long.class, pkgId, resultingRevision);
                Long validOnOld = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM signature_record WHERE package_id = ? "
                                + "AND status = 'VALID' AND revision <> ?",
                        Long.class, pkgId, resultingRevision);
                assertThat(validOnOld).isZero();

                if (signStatus == HttpStatus.OK) {
                    // 签名先于修订：它被失效；或后于修订（STALE_VERSION）。两种互斥：
                    // OK 表示签名在修订前提交，因此当前修订下不应有有效签名
                    assertThat(validOnCurrent).isZero();
                    Long invalidated = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM signature_record WHERE package_id = ? AND status = 'INVALIDATED'",
                            Long.class, pkgId);
                    assertThat(invalidated).isEqualTo(1L);
                }
                assertChainContinuousAndLinked(pkgId);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * 并发同时提交签名与查询状态，查询永不破坏写事务，事件链始终自洽。
     */
    @Test
    void concurrentReadsNeverCorruptChain() throws Exception {
        String hash = docHash("par-read");
        String pkgId = createPackage(hash, EXPIRES, List.of(
                new CreatePackageRequest.StepSpec(1, List.of("alice")),
                new CreatePackageRequest.StepSpec(2, List.of("bob"))));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> signer1 = pool.submit(() -> service.submitSignature(pkgId,
                    signRequest("pr-1", "alice", 1, hash, 1)));
            Future<?> reader = pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    service.getStatus(pkgId);
                }
            });
            signer1.get(10, TimeUnit.SECONDS);
            Future<?> signer2 = pool.submit(() -> service.submitSignature(pkgId,
                    signRequest("pr-2", "bob", 1, hash, 1)));
            signer2.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(service.getStatus(pkgId).status()).isEqualTo("COMPLETED");
        assertChainContinuousAndLinked(pkgId);
    }

    /**
     * 校验事件序号连续（1..N 无空洞）且哈希链可从创世逐环复算。
     */
    private void assertChainContinuousAndLinked(String pkgId) {
        List<ChainRow> rows = jdbc.query(
                "SELECT seq, event_type, revision, detail, previous_hash, event_hash, created_at "
                        + "FROM sign_event WHERE package_id = ? ORDER BY seq",
                (rs, n) -> new ChainRow(rs.getInt("seq"), rs.getString("event_type"),
                        rs.getInt("revision"), rs.getString("detail"),
                        rs.getString("previous_hash"), rs.getString("event_hash"),
                        rs.getTimestamp("created_at").toInstant()),
                pkgId);

        assertThat(rows).isNotEmpty();
        String expectedPrevious = Hashes.GENESIS_PREVIOUS;
        int expectedSeq = 1;
        for (ChainRow row : rows) {
            assertThat(row.seq()).isEqualTo(expectedSeq++);
            assertThat(row.previousHash()).isEqualTo(expectedPrevious);
            String canonical = "{\"createdAt\":\"" + row.createdAt() + "\",\"detail\":" + row.detail()
                    + ",\"eventType\":\"" + row.eventType() + "\",\"revision\":" + row.revision()
                    + ",\"seq\":" + row.seq() + "}";
            String recomputed = Hashes.sha256(row.previousHash() + canonical);
            assertThat(recomputed)
                    .describedAs("事件 %d 哈希链必须可复算", row.seq())
                    .isEqualTo(row.eventHash());
            expectedPrevious = row.eventHash();
        }
    }

    private record ChainRow(int seq, String eventType, int revision, String detail,
                            String previousHash, String eventHash, java.time.Instant createdAt) {
    }

    private <T> List<T> runConcurrently(int parties,
                                        java.util.function.Function<Integer, T> action)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(parties);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(parties);
        try {
            List<Future<T>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < parties; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return action.apply(index);
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
