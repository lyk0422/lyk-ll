package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.ReleaseResponse;
import com.example.starter.pesticide.dto.ApiDtos.ReportRequest;
import com.example.starter.pesticide.dto.ApiDtos.ReportResponse;
import com.example.starter.pesticide.dto.ApiDtos.TestRequest;
import com.example.starter.pesticide.dto.ApiDtos.TestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 并发与事务边界测试：并发报备唯一受理、并发重复送检拒绝、判定放行原子性。
 */
@SpringBootTest
class PesticideConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private BatchService batchService;

    @Autowired
    private TestService testService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM origin_alert");
        jdbc.update("DELETE FROM retention");
        jdbc.update("DELETE FROM disposal");
        jdbc.update("DELETE FROM admission");
        jdbc.update("DELETE FROM recheck");
        jdbc.update("DELETE FROM test_record");
        jdbc.update("DELETE FROM sample");
        jdbc.update("DELETE FROM batch");
        when(clock.instant()).thenReturn(Instant.parse("2026-09-26T08:00:00Z"));
    }

    @Test
    void 并发报备同批次唯一受理() throws Exception {
        List<Outcome<ReportResponse>> outcomes = runConcurrently(i ->
                () -> batchService.report(new ReportRequest(
                        "CR-" + i, "山东寿光", "番茄", "CB-001", new BigDecimal("10"))));
        long success = outcomes.stream().filter(o -> o.error() == null && !o.value().replayed()).count();
        long conflict = outcomes.stream().filter(Outcome::isConflict).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(THREADS - 1);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 并发重复送检仅一条受理() throws Exception {
        ReportResponse report = batchService.report(
                new ReportRequest("CR-T", "山东寿光", "番茄", "CB-002", new BigDecimal("10")));
        batchService.sample(report.batchId(), "有机磷");
        List<Outcome<TestResponse>> outcomes = runConcurrently(i ->
                () -> testService.rapidTest(new TestRequest(
                        "CT-" + i, report.batchId(), "有机磷", new BigDecimal("20"))));
        long success = outcomes.stream().filter(o -> o.error() == null).count();
        long conflict = outcomes.stream().filter(Outcome::isConflict).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(THREADS - 1);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM test_record", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 并发放行判定与准入原子完成仅一条放行记录() throws Exception {
        ReportResponse report = batchService.report(
                new ReportRequest("CR-R", "山东寿光", "番茄", "CB-003", new BigDecimal("10")));
        batchService.sample(report.batchId(), "有机磷");
        testService.rapidTest(new TestRequest("CT-R", report.batchId(), "有机磷", new BigDecimal("10")));
        List<Outcome<ReleaseResponse>> outcomes = runConcurrently(i ->
                () -> batchService.release(report.batchId()));
        // 全部线程成功（首次放行 + 幂等重放），且准入记录唯一、批次终态一致
        assertThat(outcomes).allMatch(o -> o.error() == null);
        assertThat(outcomes.stream().map(o -> o.value().admissionId()).distinct().count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed()).count()).isEqualTo(1);
        Integer admissions = jdbc.queryForObject("SELECT COUNT(*) FROM admission", Integer.class);
        assertThat(admissions).isEqualTo(1);
        String status = jdbc.queryForObject("SELECT status FROM batch WHERE id = ?",
                String.class, report.batchId());
        assertThat(status).isEqualTo("RELEASED");
    }

    private <T> List<Outcome<T>> runConcurrently(SupplierFactory<T> factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Supplier<T> task = factory.create(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return new Outcome<>(task.get(), null);
                    } catch (ApiException e) {
                        return new Outcome<T>(null, e);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @FunctionalInterface
    private interface SupplierFactory<T> {
        Supplier<T> create(int index);
    }

    private record Outcome<T>(T value, ApiException error) {
        boolean isConflict() {
            return error != null && error.status().value() == 409;
        }
    }
}
