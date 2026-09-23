package com.example.starter.spectrum.service;

import com.example.starter.spectrum.AbstractSpectrumIntegrationTest;
import com.example.starter.spectrum.LeaseMode;
import com.example.starter.spectrum.api.CancelLeaseRequest;
import com.example.starter.spectrum.api.CreateLeaseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发协调 H2 测试：多个真实并发写事务不得"分别检查后同时越过共享 EIRP 上限"。
 */
@Import(AbstractSpectrumIntegrationTest.FixedClockConfiguration.class)
class LeaseConcurrencyIntegrationTest extends AbstractSpectrumIntegrationTest {

    private static final Instant S = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant E = Instant.parse("2030-01-01T12:00:00Z");

    @Autowired
    private LeaseService leaseService;

    @Test
    void concurrentSharedCreatesCannotJointlyExceedLimit() throws Exception {
        int threads = 4;
        // 每个租约 600mW；上限 1000mW => 至多一个可以成功
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<LeaseService.ServiceResult>> futures = IntStream.range(0, threads)
                    .<java.util.concurrent.Callable<LeaseService.ServiceResult>>mapToObj(i -> () -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        CreateLeaseRequest request = new CreateLeaseRequest(
                                "cc-" + i, Set.of("CELL-A"), S, E,
                                new BigDecimal("100"), new BigDecimal("200"),
                                LeaseMode.SHARED, new BigDecimal("600"));
                        return leaseService.create(request);
                    })
                    .map(pool::submit)
                    .toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<LeaseService.ServiceResult> results = new java.util.ArrayList<>();
            for (Future<LeaseService.ServiceResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            long accepted = results.stream().filter(r -> r.httpStatus() == 201).count();
            long rejected = results.stream().filter(r -> r.httpStatus() == 409).count();
            assertThat(accepted).as("恰好一个并发创建成功").isEqualTo(1);
            assertThat(rejected).as("其余并发创建必须被串行化检查拒绝").isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 最终数据与串行化结果一致：仅一条活动租约
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lease WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lease_version", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentModifyAndCancelProduceSerializableSet() throws Exception {
        // 先建立一个共享基线租约（400mW）
        LeaseService.ServiceResult base = leaseService.create(new CreateLeaseRequest(
                "cm-base", Set.of("CELL-A"), S, E,
                new BigDecimal("100"), new BigDecimal("200"),
                LeaseMode.SHARED, new BigDecimal("400")));
        String leaseId = ((com.example.starter.spectrum.api.LeaseResponse) base.body()).leaseId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // 线程 1：取消（expectedVersion=1）
            Future<LeaseService.ServiceResult> cancelFuture = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return leaseService.cancel(new CancelLeaseRequest("cm-cancel", leaseId, 1));
            });
            // 线程 2：把功率提高到 900mW（400+900? 仅自身改功率，无其他租约，应通过——但版本竞争）
            Future<LeaseService.ServiceResult> modifyFuture = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return leaseService.modify(new com.example.starter.spectrum.api.ModifyLeaseRequest(
                        "cm-modify", leaseId, 1, Set.of("CELL-A"), S, E,
                        new BigDecimal("100"), new BigDecimal("200"),
                        LeaseMode.SHARED, new BigDecimal("900")));
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            LeaseService.ServiceResult cancelResult = cancelFuture.get(30, TimeUnit.SECONDS);
            LeaseService.ServiceResult modifyResult = modifyFuture.get(30, TimeUnit.SECONDS);

            // 二者基于同一 expectedVersion=1 竞争：恰好一个成功，另一个必须收到版本冲突
            int success = (cancelResult.httpStatus() == 200 ? 1 : 0)
                    + (modifyResult.httpStatus() == 200 ? 1 : 0);
            int mismatch = (cancelResult.httpStatus() == 409 ? 1 : 0)
                    + (modifyResult.httpStatus() == 409 ? 1 : 0);
            assertThat(success).isEqualTo(1);
            assertThat(mismatch).isEqualTo(1);

            // 终态一致：若取消先落定，则租约 CANCELLED 且无版本 2；若修改先落定，则仍 ACTIVE 且版本为 2
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM lease WHERE id = ?", String.class, leaseId);
            Integer version = jdbcTemplate.queryForObject(
                    "SELECT current_version FROM lease WHERE id = ?", Integer.class, leaseId);
            if ("CANCELLED".equals(status)) {
                assertThat(version).isEqualTo(1);
            } else {
                assertThat(status).isEqualTo("ACTIVE");
                assertThat(version).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
