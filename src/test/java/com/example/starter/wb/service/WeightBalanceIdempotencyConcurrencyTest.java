package com.example.starter.wb.service;

import com.example.starter.wb.AbstractIntegrationTest;
import com.example.starter.wb.dto.CheckinRequest;
import com.example.starter.wb.dto.CreateManifestRequest;
import com.example.starter.wb.dto.LoadItem;
import com.example.starter.wb.dto.LmcRequest;
import com.example.starter.wb.dto.ManifestResponse;
import com.example.starter.wb.dto.ReviewRequest;
import com.example.starter.wb.dto.StageRequest;
import com.example.starter.wb.exception.ConflictException;
import com.example.starter.wb.exception.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 幂等与并发测试：同键同参回放、同键异参 409；并发同岗位签字唯一成功、
 * 两岗位并发各一笔；并发舱单创建唯一有效版本。
 */
class WeightBalanceIdempotencyConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private WeightBalanceService service;

    private static final long FLIGHT_ID = 1L;

    private static List<LoadItem> items() {
        return List.of(
                new LoadItem("PASSENGER", "PAX_F", 90, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 6, new BigDecimal("1000")));
    }

    private CreateManifestRequest createReq(String requestId, BigDecimal fuel) {
        return new CreateManifestRequest(requestId, FLIGHT_ID, fuel, "lc001", items());
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResultWithoutNewVersion() {
        String rid = UUID.randomUUID().toString();
        ManifestResponse first = service.createManifest(createReq(rid, new BigDecimal("5000")));
        ManifestResponse replay = service.createManifest(createReq(rid, new BigDecimal("5000")));
        assertThat(replay.manifestId()).isEqualTo(first.manifestId());
        assertThat(replay.versionNo()).isEqualTo(first.versionNo());
        assertThat(replay.cgTowPct()).isEqualByComparingTo(first.cgTowPct());

        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest_version WHERE flight_id=1", Integer.class);
        assertThat(versions).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsConflictsAndCreatesNoVersion() {
        String rid = UUID.randomUUID().toString();
        service.createManifest(createReq(rid, new BigDecimal("5000")));
        // 仅加油量不同：指纹不一致 → 409，不产生新版本
        assertThatThrownBy(() -> service.createManifest(createReq(rid, new BigDecimal("5100"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest_version WHERE flight_id=1", Integer.class);
        assertThat(versions).isEqualTo(1);
    }

    @Test
    void lmcSameKeyReplaysAndDifferentKeyParamsConflicts() {
        service.createManifest(createReq(UUID.randomUUID().toString(), new BigDecimal("5000")));
        service.checkin(FLIGHT_ID, new CheckinRequest(UUID.randomUUID().toString(),
                items(), new BigDecimal("5000"), "a"));
        service.confirmLoad(FLIGHT_ID, new StageRequest(UUID.randomUUID().toString(), "loader"));

        String rid = UUID.randomUUID().toString();
        LmcRequest req = new LmcRequest(rid, 2, "PAX_F", new BigDecimal("80"),
                new BigDecimal("100"), "CARGO", "CARGO_F", BigDecimal.ZERO, "lc001");
        ManifestResponse first = service.applyLmc(FLIGHT_ID, req);
        ManifestResponse replay = service.applyLmc(FLIGHT_ID, req);
        assertThat(replay.lmcPaxDelta()).isEqualTo(first.lmcPaxDelta()).isEqualTo(2);

        LmcRequest tampered = new LmcRequest(rid, 3, "PAX_F", new BigDecimal("80"),
                new BigDecimal("100"), "CARGO", "CARGO_F", BigDecimal.ZERO, "lc001");
        assertThatThrownBy(() -> service.applyLmc(FLIGHT_ID, tampered))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");
    }

    @Test
    void failedFirstResultIsAlsoReplayedForSameKeyAndParams() {
        // 首次因业载超限 422（业务事务回滚），同键同参再次请求回放同一失败，不产生新版本。
        String rid = UUID.randomUUID().toString();
        List<LoadItem> over = List.of(
                new LoadItem("PASSENGER", "PAX_F", 100, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 7, new BigDecimal("1001")));
        CreateManifestRequest req = new CreateManifestRequest(
                rid, FLIGHT_ID, new BigDecimal("5000"), "lc001", over);
        assertThatThrownBy(() -> service.createManifest(req))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("MZFW-OEW");
        assertThatThrownBy(() -> service.createManifest(req))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("MZFW-OEW");
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest_version WHERE flight_id=1", Integer.class);
        assertThat(versions).isZero();
        Integer idem = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id=?", Integer.class, rid);
        assertThat(idem).isEqualTo(1);
    }

    @Test
    void concurrentSameRoleReviewHasExactlyOneSuccess() throws Exception {
        service.createManifest(createReq(UUID.randomUUID().toString(), new BigDecimal("5000")));
        service.checkin(FLIGHT_ID, new CheckinRequest(UUID.randomUUID().toString(),
                items(), new BigDecimal("5000"), "a"));
        service.confirmLoad(FLIGHT_ID, new StageRequest(UUID.randomUUID().toString(), "loader"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.review(FLIGHT_ID, new ReviewRequest(
                            UUID.randomUUID().toString(), "LOAD_CONTROLLER", "lc00" + n));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_confirmation WHERE role_code='LOAD_CONTROLLER'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentRolesBothSucceedAndManifestFreezes() throws Exception {
        service.createManifest(createReq(UUID.randomUUID().toString(), new BigDecimal("5000")));
        service.checkin(FLIGHT_ID, new CheckinRequest(UUID.randomUUID().toString(),
                items(), new BigDecimal("5000"), "a"));
        service.confirmLoad(FLIGHT_ID, new StageRequest(UUID.randomUUID().toString(), "loader"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable lc = () -> {
            awaitQuiet(start);
            service.review(FLIGHT_ID, new ReviewRequest(
                    UUID.randomUUID().toString(), "LOAD_CONTROLLER", "lc001"));
        };
        Runnable fd = () -> {
            awaitQuiet(start);
            service.review(FLIGHT_ID, new ReviewRequest(
                    UUID.randomUUID().toString(), "FLIGHT_DISPATCHER", "fd002"));
        };
        Future<?> f1 = pool.submit(lc);
        Future<?> f2 = pool.submit(fd);
        start.countDown();
        f1.get(20, TimeUnit.SECONDS);
        f2.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        ManifestResponse active = service.getActive(FLIGHT_ID);
        assertThat(active.confirmedRoles())
                .containsExactlyInAnyOrder("LOAD_CONTROLLER", "FLIGHT_DISPATCHER");
        assertThat(active.status()).isEqualTo("REVIEWED");
        assertThat(active.frozen()).isTrue();
    }

    @Test
    void concurrentCreateHasExactlyOneActiveVersion() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.createManifest(createReq(UUID.randomUUID().toString(), new BigDecimal("5000")));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest_version WHERE active_flag=1", Integer.class);
        assertThat(active).isEqualTo(1);
    }

    private static void awaitQuiet(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
