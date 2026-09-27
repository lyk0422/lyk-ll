package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.ReportRequest;
import com.example.starter.pesticide.dto.ApiDtos.ReportResponse;
import com.example.starter.pesticide.dto.ApiDtos.TestRequest;
import com.example.starter.pesticide.dto.ApiDtos.TestResponse;
import com.example.starter.pesticide.dto.JointDtos.AssignmentItem;
import com.example.starter.pesticide.dto.JointDtos.JointAssignRequest;
import com.example.starter.pesticide.dto.JointDtos.JointCreateRequest;
import com.example.starter.pesticide.dto.JointDtos.JointCreateResponse;
import com.example.starter.pesticide.dto.JointDtos.JointDetermineRequest;
import com.example.starter.pesticide.dto.JointDtos.JointDetermineResponse;
import com.example.starter.pesticide.dto.JointDtos.JointResultRequest;
import com.example.starter.pesticide.dto.JointDtos.JointResultResponse;
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
 * 联检并发与事务边界测试：并发建单唯一受理、并发同键结果提交仅一条受理、
 * 并发复测使版本失配则判定零写入。
 */
@SpringBootTest
class JointConcurrencyTest {

    private static final int THREADS = 8;
    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-09-26T09:00:00Z");

    @Autowired
    private BatchService batchService;

    @Autowired
    private TestService testService;

    @Autowired
    private JointService jointService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM joint_determination_item");
        jdbc.update("DELETE FROM joint_determination");
        jdbc.update("DELETE FROM joint_result");
        jdbc.update("DELETE FROM joint_assignment");
        jdbc.update("DELETE FROM joint_order_item");
        jdbc.update("DELETE FROM joint_order");
        jdbc.update("DELETE FROM joint_request_log");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM origin_alert");
        jdbc.update("DELETE FROM retention");
        jdbc.update("DELETE FROM disposal");
        jdbc.update("DELETE FROM admission");
        jdbc.update("DELETE FROM recheck");
        jdbc.update("DELETE FROM test_record");
        jdbc.update("DELETE FROM sample");
        jdbc.update("DELETE FROM batch");
        when(clock.instant()).thenReturn(T0);
    }

    @Test
    void 并发建单同批次唯一受理() throws Exception {
        long[] frozen = frozenSuspectBatch("CC1");
        List<Outcome<JointCreateResponse>> outcomes = runConcurrently(THREADS, i ->
                () -> jointService.create(new JointCreateRequest(
                        "JCC-" + i, frozen[0], frozen[1], List.of("毒死蜱", "克百威"))));
        long success = outcomes.stream().filter(o -> o.error() == null).count();
        long rejected = outcomes.stream().filter(o -> o.isStatus(422)).count();
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(THREADS - 1);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM joint_order", Integer.class);
        assertThat(orders).isEqualTo(1);
        Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM joint_order_item", Integer.class);
        assertThat(items).isEqualTo(2);
    }

    @Test
    void 并发同键同参结果提交仅一条受理其余重放() throws Exception {
        long orderId = assignedOrder("CC2");
        JointResultRequest request = new JointResultRequest("JCR-SAME", orderId, 2L,
                "毒死蜱", "S-CC2-1", new BigDecimal("0.01"), OCCURRED, false);
        List<Outcome<JointResultResponse>> outcomes = runConcurrently(THREADS, i -> () ->
                jointService.submitResult(request));
        assertThat(outcomes).allMatch(o -> o.error() == null);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed()).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o.value().replayed()).count())
                .isEqualTo(THREADS - 1);
        Integer results = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_result WHERE order_id = ?", Integer.class, orderId);
        assertThat(results).isEqualTo(1);
        // 版本只推进一次
        Integer version = jdbc.queryForObject(
                "SELECT version FROM joint_order WHERE id = ?", Integer.class, orderId);
        assertThat(version).isEqualTo(3);
    }

    @Test
    void 并发复测使版本失配则判定零写入() throws Exception {
        long orderId = assignedOrder("CC3");
        // 两个项目均已有首次结果，当前版本 4
        submit("JCR-CC3-1", orderId, 2L, "毒死蜱", "S-CC3-1", "0.01", false);
        submit("JCR-CC3-2", orderId, 3L, "克百威", "S-CC3-2", "0.01", false);
        // 并发：复测（expectedVersion=4）与综合判定（expectedVersion=4）竞争，仅一方成功
        List<Outcome<Object>> outcomes = runConcurrently(THREADS, i -> () -> {
            if (i % 2 == 0) {
                return jointService.submitResult(new JointResultRequest(
                        "JCR-CC3-R" + i, orderId, 4L, "毒死蜱", "S-CC3-1",
                        new BigDecimal("0.02"), OCCURRED, true));
            }
            return jointService.determine(new JointDetermineRequest("JDC-CC3-" + i, orderId, 4L));
        });
        long success = outcomes.stream().filter(o -> o.error() == null).count();
        long conflict = outcomes.stream().filter(o -> o.isStatus(409)).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(THREADS - 1);
        Object winner = outcomes.stream().filter(o -> o.error() == null)
                .findFirst().orElseThrow().value();
        Integer resultCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_result WHERE order_id = ?", Integer.class, orderId);
        Integer determinationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_determination WHERE order_id = ?", Integer.class, orderId);
        if (winner instanceof JointDetermineResponse) {
            // 判定胜：判定证据完整，复测零写入（结果仍为 2 条）
            assertThat(determinationCount).isEqualTo(1);
            assertThat(resultCount).isEqualTo(2);
            Integer evidence = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM joint_determination_item", Integer.class);
            assertThat(evidence).isEqualTo(2);
        } else {
            // 复测胜：判定零写入
            assertThat(determinationCount).isZero();
            assertThat(resultCount).isEqualTo(3);
        }
    }

    @Test
    void 并发分样同键重放且样本号唯一() throws Exception {
        long[] frozen = frozenSuspectBatch("CC4");
        JointCreateResponse order = jointService.create(new JointCreateRequest(
                "JCC-CC4", frozen[0], frozen[1], List.of("毒死蜱", "克百威")));
        JointAssignRequest request = new JointAssignRequest("JCA-CC4", order.orderId(), 1L,
                List.of(new AssignmentItem("毒死蜱", "S-CC4-1", "AG-A"),
                        new AssignmentItem("克百威", "S-CC4-2", "AG-A")));
        List<Outcome<com.example.starter.pesticide.dto.JointDtos.JointAssignResponse>> outcomes =
                runConcurrently(THREADS, i -> () -> jointService.assign(request));
        assertThat(outcomes).allMatch(o -> o.error() == null);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed()).count()).isEqualTo(1);
        Integer assignments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_assignment WHERE order_id = ?",
                Integer.class, order.orderId());
        assertThat(assignments).isEqualTo(2);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM joint_order WHERE id = ?", Integer.class, order.orderId());
        assertThat(version).isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private long[] frozenSuspectBatch(String suffix) {
        ReportResponse report = batchService.report(new ReportRequest(
                "CR-" + suffix, "山东寿光", "番茄", "CB-" + suffix, new BigDecimal("5")));
        batchService.sample(report.batchId(), "有机磷");
        TestResponse test = testService.rapidTest(new TestRequest(
                "CT-" + suffix, report.batchId(), "有机磷", new BigDecimal("60")));
        assertThat(test.result()).isEqualTo("SUSPECT");
        return new long[]{report.batchId(), test.testId()};
    }

    private long assignedOrder(String suffix) {
        long[] frozen = frozenSuspectBatch(suffix);
        JointCreateResponse order = jointService.create(new JointCreateRequest(
                "JCC-" + suffix, frozen[0], frozen[1], List.of("毒死蜱", "克百威")));
        jointService.assign(new JointAssignRequest("JCA-" + suffix, order.orderId(), 1L,
                List.of(new AssignmentItem("毒死蜱", "S-" + suffix + "-1", "AG-A"),
                        new AssignmentItem("克百威", "S-" + suffix + "-2", "AG-A"))));
        return order.orderId();
    }

    private void submit(String requestId, long orderId, long version, String item,
                        String sampleNo, String concentration, boolean remeasure) {
        jointService.submitResult(new JointResultRequest(requestId, orderId, version, item,
                sampleNo, new BigDecimal(concentration), OCCURRED, remeasure));
    }

    private <T> List<Outcome<T>> runConcurrently(int threads, SupplierFactory<T> factory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
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
                outcomes.add(future.get(30, TimeUnit.SECONDS));
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
        boolean isStatus(int status) {
            return error != null && error.status().value() == status;
        }
    }
}
