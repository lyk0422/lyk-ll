package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointAssignItem;
import com.example.starter.pesticide.dto.ApiDtos.JointAssignRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointAssignResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointJudgeRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointJudgeResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointOrderCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointOrderResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointResultResponse;
import com.example.starter.pesticide.dto.ApiDtos.ReportRequest;
import com.example.starter.pesticide.dto.ApiDtos.ReportResponse;
import com.example.starter.pesticide.dto.ApiDtos.TestRequest;
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
 * 联检并发与事务边界测试：并发建单唯一受理、同键重放、并发分样、
 * 并发复测使版本失配判定零写入、并发判定仅一条。
 */
@SpringBootTest
class JointOrderConcurrencyTest {

    private static final int THREADS = 8;
    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

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
        jdbc.update("DELETE FROM joint_idempotency");
        jdbc.update("DELETE FROM joint_supplement");
        jdbc.update("DELETE FROM joint_judgment");
        jdbc.update("DELETE FROM joint_result");
        jdbc.update("DELETE FROM joint_assignment");
        jdbc.update("DELETE FROM joint_order_item");
        jdbc.update("DELETE FROM joint_order");
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
    void 并发建单同键同参仅一单_其余重放() throws Exception {
        long batchId = frozenBatch("CR-J1", "CT-J1", "CJB-001");
        JointOrderCreateRequest request = new JointOrderCreateRequest("CJO-1", batchId,
                "SYN-LAB-1", List.of("SYN-A", "SYN-B"), T0);
        List<Outcome<JointOrderResponse>> outcomes = runConcurrently(THREADS, i -> () ->
                jointService.createOrder(request));
        assertThat(outcomes).allMatch(o -> o.error() == null);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed()).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o.value().replayed()).count())
                .isEqualTo(THREADS - 1);
        assertThat(outcomes.stream().map(o -> o.value().orderId()).distinct().count()).isEqualTo(1);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM joint_order", Integer.class);
        assertThat(orders).isEqualTo(1);
        Integer idempotencies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_idempotency", Integer.class);
        assertThat(idempotencies).isEqualTo(1);
    }

    @Test
    void 并发建单同批次异键仅一单受理() throws Exception {
        long batchId = frozenBatch("CR-J2", "CT-J2", "CJB-002");
        List<Outcome<JointOrderResponse>> outcomes = runConcurrently(THREADS, i -> () ->
                jointService.createOrder(new JointOrderCreateRequest("CJO-2-" + i, batchId,
                        "SYN-LAB-1", List.of("SYN-A", "SYN-B"), T0)));
        long success = outcomes.stream().filter(o -> o.error() == null && !o.value().replayed())
                .count();
        assertThat(success).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o.error() != null).count())
                .isEqualTo(THREADS - 1);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM joint_order", Integer.class);
        assertThat(orders).isEqualTo(1);
    }

    @Test
    void 并发分样仅一次成功_样本号全局唯一() throws Exception {
        long batchId = frozenBatch("CR-J3", "CT-J3", "CJB-003");
        JointOrderResponse order = jointService.createOrder(new JointOrderCreateRequest(
                "CJO-3", batchId, "SYN-LAB-1", List.of("SYN-A", "SYN-B"), T0));
        List<Outcome<JointAssignResponse>> outcomes = runConcurrently(THREADS, i -> () ->
                jointService.assign(order.orderId(), new JointAssignRequest("CJA-3-" + i, 1,
                        List.of(new JointAssignItem("SYN-A", "CSN-300"),
                                new JointAssignItem("SYN-B", "CSN-301")), T0)));
        long success = outcomes.stream().filter(o -> o.error() == null).count();
        assertThat(success).isEqualTo(1);
        Integer assignments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_assignment", Integer.class);
        assertThat(assignments).isEqualTo(2);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM joint_order WHERE id = ?", Integer.class, order.orderId());
        assertThat(version).isEqualTo(2);
    }

    @Test
    void 并发复测使版本失配则判定零写入() throws Exception {
        long batchId = frozenBatch("CR-J4", "CT-J4", "CJB-004");
        JointOrderResponse order = jointService.createOrder(new JointOrderCreateRequest(
                "CJO-4", batchId, "SYN-LAB-1", List.of("SYN-A", "SYN-B"), T0));
        jointService.assign(order.orderId(), new JointAssignRequest("CJA-4", 1,
                List.of(new JointAssignItem("SYN-A", "CSN-400"),
                        new JointAssignItem("SYN-B", "CSN-401")), T0));
        jointService.submitResult(order.orderId(), new JointResultRequest("CJE-4A", 2,
                "SYN-A", new BigDecimal("0.05"), T0));
        jointService.submitResult(order.orderId(), new JointResultRequest("CJE-4B", 3,
                "SYN-B", new BigDecimal("0.1"), T0));
        // 版本 4：并发复测与综合判定争抢同一版本，恰一个成功
        List<Outcome<Object>> outcomes = runConcurrently(2, i -> () -> {
            if (i == 0) {
                return jointService.submitResult(order.orderId(), new JointResultRequest(
                        "CJE-4C", 4, "SYN-A", new BigDecimal("0.06"), T0));
            }
            return jointService.judge(order.orderId(), new JointJudgeRequest("CJJ-4", 4, T0));
        });
        long success = outcomes.stream().filter(o -> o.error() == null).count();
        long conflict = outcomes.stream().filter(Outcome::isConflict).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        boolean judged = outcomes.stream()
                .anyMatch(o -> o.error() == null && o.value() instanceof JointJudgeResponse);
        Integer judgments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_judgment", Integer.class);
        String batchStatus = jdbc.queryForObject(
                "SELECT status FROM batch WHERE id = ?", String.class, batchId);
        Integer orderVersion = jdbc.queryForObject(
                "SELECT version FROM joint_order WHERE id = ?", Integer.class, order.orderId());
        if (judged) {
            // 判定胜出：判定与放行原子完成，复测版本失配零写入
            assertThat(judgments).isEqualTo(1);
            assertThat(batchStatus).isEqualTo("RELEASED");
            assertThat(orderVersion).isEqualTo(5);
            Integer itemVersions = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(item_version), 0) FROM joint_result"
                            + " WHERE order_id = ? AND item_code = 'SYN-A'",
                    Integer.class, order.orderId());
            assertThat(itemVersions).isEqualTo(1);
        } else {
            // 复测胜出：判定版本失配零写入，批次仍冻结待定
            assertThat(judgments).isEqualTo(0);
            assertThat(batchStatus).isEqualTo("FROZEN");
            assertThat(orderVersion).isEqualTo(5);
            Integer itemVersions = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(item_version), 0) FROM joint_result"
                            + " WHERE order_id = ? AND item_code = 'SYN-A'",
                    Integer.class, order.orderId());
            assertThat(itemVersions).isEqualTo(2);
        }
        Integer admissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admission WHERE batch_id = ?", Integer.class, batchId);
        assertThat(admissions).isEqualTo(judged ? 1 : 0);
    }

    @Test
    void 并发判定同键仅一条判定与放行() throws Exception {
        long batchId = frozenBatch("CR-J5", "CT-J5", "CJB-005");
        JointOrderResponse order = jointService.createOrder(new JointOrderCreateRequest(
                "CJO-5", batchId, "SYN-LAB-1", List.of("SYN-A", "SYN-B"), T0));
        jointService.assign(order.orderId(), new JointAssignRequest("CJA-5", 1,
                List.of(new JointAssignItem("SYN-A", "CSN-500"),
                        new JointAssignItem("SYN-B", "CSN-501")), T0));
        jointService.submitResult(order.orderId(), new JointResultRequest("CJE-5A", 2,
                "SYN-A", new BigDecimal("0.05"), T0));
        jointService.submitResult(order.orderId(), new JointResultRequest("CJE-5B", 3,
                "SYN-B", new BigDecimal("0.1"), T0));
        JointJudgeRequest judgeRequest = new JointJudgeRequest("CJJ-5", 4, T0);
        List<Outcome<JointJudgeResponse>> outcomes = runConcurrently(THREADS, i -> () ->
                jointService.judge(order.orderId(), judgeRequest));
        assertThat(outcomes).allMatch(o -> o.error() == null);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed()).count()).isEqualTo(1);
        assertThat(outcomes.stream().map(o -> o.value().judgmentId()).distinct().count())
                .isEqualTo(1);
        Integer judgments = jdbc.queryForObject("SELECT COUNT(*) FROM joint_judgment",
                Integer.class);
        assertThat(judgments).isEqualTo(1);
        Integer admissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admission WHERE batch_id = ?", Integer.class, batchId);
        assertThat(admissions).isEqualTo(1);
        String batchStatus = jdbc.queryForObject(
                "SELECT status FROM batch WHERE id = ?", String.class, batchId);
        assertThat(batchStatus).isEqualTo("RELEASED");
    }

    private long frozenBatch(String reportReqId, String testReqId, String batchNo) {
        ReportResponse report = batchService.report(new ReportRequest(reportReqId, "山东寿光",
                "番茄", batchNo, new BigDecimal("5")));
        batchService.sample(report.batchId(), "有机磷");
        testService.rapidTest(new TestRequest(testReqId, report.batchId(), "有机磷",
                new BigDecimal("60")));
        return report.batchId();
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
