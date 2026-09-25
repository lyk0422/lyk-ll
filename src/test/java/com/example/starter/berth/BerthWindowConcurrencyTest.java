package com.example.starter.berth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界测试（真实线程并发 + H2 唯一约束/行锁）：
 * 并发同键同参仅一条记录且响应一致；并发同键异参仅一个参数集成功；
 * 并发审批重叠窗口仅一个获批。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class BerthWindowConcurrencyTest {

    private static final String D = "2026-10-10T";
    private static final int THREADS = 8;

    @Autowired
    private BerthWindowService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM window_pilot");
        jdbc.update("DELETE FROM berth_window");
        clock.setInstant(TestClockConfig.BASE);
        executor = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private ApplyWindowRequest req(String requestId, String vessel, String berthId,
                                   String start, String end, int pilots) {
        return new ApplyWindowRequest(requestId, vessel, berthId,
                Instant.parse(start), Instant.parse(end), new BigDecimal("5"), pilots, null);
    }

    private int windowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM berth_window", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 并发提交任务并收集结果；所有任务在同一起跑线放行，整体设置超时。
     */
    private <T> List<T> runConcurrently(List<java.util.concurrent.Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (java.util.concurrent.Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void concurrentSameKeySameParamsSingleRecordConsistentResponse() throws Exception {
        ApplyWindowRequest request = req("R-CONC", "MV-SAME", "BERTH-1",
                D + "06:00:00Z", D + "10:00:00Z", 1);
        List<java.util.concurrent.Callable<WindowResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> service.apply(request));
        }

        List<WindowResponse> responses = runConcurrently(tasks);

        // 仅一条记录；所有并发调用均成功且响应一致
        assertThat(windowCount()).isEqualTo(1);
        assertThat(responses).hasSize(THREADS);
        WindowResponse first = responses.get(0);
        assertThat(first.status()).isEqualTo(WindowStatus.PENDING);
        assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(first));
    }

    @Test
    void concurrentSameKeyDifferentParamsExactlyOneVariantWins() throws Exception {
        ApplyWindowRequest variantA = req("R-CONC2", "MV-AAA", "BERTH-1",
                D + "06:00:00Z", D + "10:00:00Z", 1);
        ApplyWindowRequest variantB = req("R-CONC2", "MV-BBB", "BERTH-1",
                D + "06:00:00Z", D + "10:00:00Z", 1);
        List<java.util.concurrent.Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            ApplyWindowRequest variant = (i % 2 == 0) ? variantA : variantB;
            tasks.add(() -> {
                try {
                    return "OK:" + service.apply(variant).vesselName();
                } catch (ApiException ex) {
                    return "ERR:" + ex.code();
                }
            });
        }

        List<String> results = runConcurrently(tasks);

        // 仅一条记录；成功响应全部指向同一变体；其余为 409 同键异参
        assertThat(windowCount()).isEqualTo(1);
        List<String> winners = results.stream().filter(r -> r.startsWith("OK:")).toList();
        assertThat(winners).isNotEmpty();
        String winningVessel = winners.get(0);
        assertThat(winners).allSatisfy(r -> assertThat(r).isEqualTo(winningVessel));
        assertThat(results.stream().filter(r -> r.equals("ERR:REQUEST_ID_CONFLICT")).count())
                .isEqualTo(THREADS - winners.size());
        String storedVessel = jdbc.queryForObject(
                "SELECT vessel_name FROM berth_window WHERE request_id = 'R-CONC2'", String.class);
        assertThat("OK:" + storedVessel).isEqualTo(winningVessel);
    }

    @Test
    void concurrentApprovalOfOverlappingWindowsOnlyOneApproved() throws Exception {
        service.apply(req("W1", "MV-1", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", 0));
        service.apply(req("W2", "MV-2", "BERTH-1", D + "08:00:00Z", D + "12:00:00Z", 0));

        List<java.util.concurrent.Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String requestId = (i % 2 == 0) ? "W1" : "W2";
            tasks.add(() -> {
                try {
                    return "OK:" + service.approve(requestId).requestId();
                } catch (ApiException ex) {
                    return "ERR:" + ex.code();
                }
            });
        }

        List<String> results = runConcurrently(tasks);

        // 串行化审批下仅一个窗口获批；另一窗口的审批全部因冲突失败
        List<String> approved = results.stream().filter(r -> r.startsWith("OK:")).toList();
        assertThat(approved).isNotEmpty();
        String winner = approved.get(0);
        assertThat(winner).isIn("OK:W1", "OK:W2");
        assertThat(approved).allSatisfy(r -> assertThat(r).isEqualTo(winner));
        assertThat(results.stream().filter(r -> r.equals("ERR:BERTH_WINDOW_CONFLICT")).count())
                .isEqualTo(THREADS - approved.size());

        Integer approvedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM berth_window WHERE status = 'APPROVED'", Integer.class);
        assertThat(approvedCount).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyReplayAfterCommitReturnsStoredResult() throws Exception {
        // 先串行建立记录，再并发重放：全部返回首次结果
        ApplyWindowRequest request = req("R-CONC3", "MV-FIRST", "BERTH-1",
                D + "06:00:00Z", D + "10:00:00Z", 1);
        WindowResponse first = service.apply(request);

        List<java.util.concurrent.Callable<WindowResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> service.apply(request));
        }
        List<WindowResponse> responses = runConcurrently(tasks);

        assertThat(windowCount()).isEqualTo(1);
        assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(first));
    }
}
