package com.example.starter.water;

import com.example.starter.water.web.ApiDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 真实并发测试：同键并发重放、并发申报额度快照、读数并发去重、
 * 限取与申报竞态、封存与申报竞态。均通过门闩协调真实并发并设置超时。
 */
@Import(TestClockConfig.class)
class WaterConcurrencyTest extends AbstractWaterTest {

    /** 并发执行两个任务，两者就绪后同时放行。 */
    private static <T> List<T> runConcurrently(Callable<T> first, Callable<T> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<T> wrappedFirst = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return first.call();
            };
            Callable<T> wrappedSecond = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return second.call();
            };
            Future<T> f1 = pool.submit(wrappedFirst);
            Future<T> f2 = pool.submit(wrappedSecond);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            results.add(f1.get(30, TimeUnit.SECONDS));
            results.add(f2.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(60)
    void concurrentSameRequestId_replaysSingleResult() throws Exception {
        String user = newUser("R1", "1000");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ApiDtos.DeclarationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return declare("req-race", user, "2026-01", "500");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ApiDtos.DeclarationResponse> results = new ArrayList<>();
            for (Future<ApiDtos.DeclarationResponse> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            // 全部返回首次结果：同一版本号、同一受理量
            assertThat(results).allSatisfy(r -> {
                assertThat(r.status()).isEqualTo("CONFIRMED");
                assertThat(r.version()).isEqualTo(1);
                assertThat(r.acceptedAmount()).isEqualByComparingTo(new BigDecimal("500"));
            });
            // 只产生一个确认版本
            assertThat(service.quota(user, 2026).confirmedTotal())
                    .isEqualByComparingTo(new BigDecimal("500"));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(60)
    void concurrentDeclarations_quotaSnapshotConsistent() throws Exception {
        String user = newUser("R1", "1000");
        // 两笔并发申报各 600，总额 1000：只有一笔能受理，另一笔必须超额拒绝
        List<ApiDtos.DeclarationResponse> results = runConcurrently(
                () -> declare("req-c1", user, "2026-01", "600"),
                () -> declare("req-c2", user, "2026-02", "600"));

        long confirmed = results.stream().filter(r -> r.status().equals("CONFIRMED")).count();
        long rejected = results.stream().filter(r -> r.status().equals("REJECTED")).count();
        assertThat(confirmed).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status().equals("REJECTED")))
                .allSatisfy(r -> assertThat(r.reason()).isEqualTo("QUOTA_EXCEEDED"));

        // 最终确认合计恰为 600，不超额度
        assertThat(service.quota(user, 2026).confirmedTotal())
                .isEqualByComparingTo(new BigDecimal("600"));
    }

    @Test
    @Timeout(60)
    void concurrentDuplicateReadings_countedOnce() throws Exception {
        String user = newUser("R1", "1000");
        String point = newMeterPoint(user);
        report(point, "2026-01-01T00:00:00Z", "0");

        List<ApiDtos.ReadingResponse> results = runConcurrently(
                () -> report(point, "2026-01-31T00:00:00Z", "100"),
                () -> report(point, "2026-01-31T00:00:00Z", "100"));

        // 一笔入库、一笔判重，月累计不重计
        assertThat(results.stream().filter(ApiDtos.ReadingResponse::duplicate).count())
                .isEqualTo(1);
        assertThat(service.usage(user, "2026-01").actualUsage())
                .isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @Timeout(120)
    void restrictionRace_uniqueOrderedOutcome() throws Exception {
        boolean sawFull = false;
        boolean sawHalved = false;
        // 多轮竞态：每轮唯一结果，且不允许“限取生效同刻仍按全额受理”的不一致态
        for (int round = 0; round < 20; round++) {
            cleanTables();
            String user = newUser("RACE", "1000");
            String requestId = "req-race-" + round;
            List<ApiDtos.DeclarationResponse> results = runConcurrently(
                    () -> declare(requestId, user, "2026-06", "100"),
                    () -> {
                        service.triggerRestriction(new ApiDtos.RestrictionRequest("RACE"));
                        return null;
                    });
            ApiDtos.DeclarationResponse declaration = results.get(0);
            assertThat(declaration.status()).isEqualTo("CONFIRMED");
            if (declaration.restricted()) {
                // 限取先生效：必须减半受理
                assertThat(declaration.acceptedAmount())
                        .isEqualByComparingTo(new BigDecimal("50"));
                sawHalved = true;
            } else {
                // 申报先受理：必须全额，且限取在其后生效
                assertThat(declaration.acceptedAmount())
                        .isEqualByComparingTo(new BigDecimal("100"));
                sawFull = true;
            }
            // 终态：河段已进入限取
            assertThat(service.triggerRestriction(new ApiDtos.RestrictionRequest("RACE")).active())
                    .isTrue();
        }
        // 两种合法时序都真实出现过，证明竞态被真实协调而非单侧空转
        assertThat(sawFull).as("至少一轮申报先于限取生效").isTrue();
        assertThat(sawHalved).as("至少一轮限取先于申报受理").isTrue();
    }

    @Test
    @Timeout(120)
    void sealRace_uniqueOrderedOutcome() throws Exception {
        // 确定性时序两端已由 WaterServiceTest 覆盖（先申报后封存确认、先封存后申报拒绝）；
        // 本用例聚焦并发轮中每轮结果唯一且与先后时序一致。
        for (int round = 0; round < 20; round++) {
            cleanTables();
            String user = newUser("R1", "1000");
            String requestId = "req-seal-" + round;
            // 交替提交顺序，避免线程池调度偏向固定一方
            Callable<ApiDtos.DeclarationResponse> declareTask =
                    () -> declare(requestId, user, "2026-06", "100");
            Callable<ApiDtos.DeclarationResponse> sealTask = () -> {
                service.seal(new ApiDtos.SealRequest(user, 2026));
                return null;
            };
            List<ApiDtos.DeclarationResponse> results = round % 2 == 0
                    ? runConcurrently(declareTask, sealTask)
                    : runConcurrently(sealTask, declareTask);
            ApiDtos.DeclarationResponse declaration = results.get(0) != null
                    ? results.get(0) : results.get(1);
            if (declaration.status().equals("CONFIRMED")) {
                // 申报先于封存：受理成功且为全额
                assertThat(declaration.acceptedAmount())
                        .isEqualByComparingTo(new BigDecimal("100"));
            } else {
                // 封存先生效：迟到申报必须拒绝，不允许出现中间态
                assertThat(declaration.status()).isEqualTo("REJECTED");
                assertThat(declaration.reason()).isEqualTo("PLAN_SEALED");
            }
            // 终态一致：年度已封存，重复封存幂等成功
            assertThat(service.seal(new ApiDtos.SealRequest(user, 2026)).alreadySealed()).isTrue();
        }
    }
}
