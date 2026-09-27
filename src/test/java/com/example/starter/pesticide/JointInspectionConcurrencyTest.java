package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointAllocEntry;
import com.example.starter.pesticide.dto.ApiDtos.JointAllocateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointAllocateResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointDecisionRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointItemVersionEntry;
import com.example.starter.pesticide.dto.ApiDtos.JointResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointResultResponse;
import com.example.starter.pesticide.dto.ApiDtos.ReportRequest;
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
 * 联检并发与事务边界测试：并发创建唯一受理、并发分样版本门控、
 * 并发复测项版本递增唯一、并发复测与判定竞争时判定零写入。
 */
@SpringBootTest
class JointInspectionConcurrencyTest {

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
        jdbc.update("DELETE FROM joint_decision");
        jdbc.update("DELETE FROM joint_result");
        jdbc.update("DELETE FROM joint_allocation");
        jdbc.update("DELETE FROM joint_item");
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
    void 并发创建同请求号唯一受理其余重放() throws Exception {
        long testId = frozenBatch("CC-1");
        long batchId = jdbc.queryForObject("SELECT id FROM batch", Long.class);
        JointCreateRequest request = new JointCreateRequest("JC-C1", batchId, testId,
                "LAB-A", List.of("毒死蜱", "克百威"));
        List<Outcome<JointResponse>> outcomes = runConcurrently(THREADS, i ->
                () -> jointService.create(request));
        long created = outcomes.stream().filter(o -> o.error() == null && !o.value().replayed()).count();
        long replayed = outcomes.stream().filter(o -> o.error() == null && o.value().replayed()).count();
        assertThat(created).isEqualTo(1);
        assertThat(created + replayed).isEqualTo(THREADS);
        assertThat(outcomes.stream().map(o -> o.value().jointId()).distinct().count()).isEqualTo(1);
        Integer joints = jdbc.queryForObject("SELECT COUNT(*) FROM joint_order", Integer.class);
        assertThat(joints).isEqualTo(1);
        Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM joint_item", Integer.class);
        assertThat(items).isEqualTo(2);
    }

    @Test
    void 并发分样仅一单成功其余版本失配() throws Exception {
        long jointId = openJoint("CC-2");
        List<Outcome<JointAllocateResponse>> outcomes = runConcurrently(THREADS, i ->
                () -> jointService.allocate(jointId, new JointAllocateRequest("JA-C2-" + i, 1,
                        List.of(new JointAllocEntry("毒死蜱", "SN-C2-" + i + "-1"),
                                new JointAllocEntry("克百威", "SN-C2-" + i + "-2")))));
        long success = outcomes.stream().filter(o -> o.error() == null).count();
        long rejected = outcomes.stream()
                .filter(o -> o.error() != null
                        && (o.error().status().value() == 409 || o.error().status().value() == 422))
                .count();
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(THREADS - 1);
        Integer allocations = jdbc.queryForObject("SELECT COUNT(*) FROM joint_allocation", Integer.class);
        assertThat(allocations).isEqualTo(1);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM joint_order WHERE id = ?", Integer.class, jointId);
        assertThat(version).isEqualTo(2);
    }

    @Test
    void 并发复测同项仅一个生效项版本唯一递增() throws Exception {
        long jointId = decidedReadyJoint("CC-3");
        // 当前联检版本 4，两项结果均为 v1；并发复测毒死蜱
        List<Outcome<JointResultResponse>> outcomes = runConcurrently(THREADS, i ->
                () -> jointService.addResult(jointId, new JointResultRequest("JR-C3-" + i, 4,
                        "毒死蜱", "SN-CC-3-1", new BigDecimal("0.03"), T0)));
        long success = outcomes.stream().filter(o -> o.error() == null && !o.value().supplemental()).count();
        long conflict = outcomes.stream().filter(Outcome::isConflict).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(THREADS - 1);
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_result WHERE joint_id = ? AND item = '毒死蜱'",
                Integer.class, jointId);
        assertThat(versions).isEqualTo(2);
        Integer maxVersion = jdbc.queryForObject(
                "SELECT MAX(version) FROM joint_result WHERE joint_id = ? AND item = '毒死蜱'",
                Integer.class, jointId);
        assertThat(maxVersion).isEqualTo(2);
    }

    @Test
    void 并发复测与判定竞争判定零写入或复测变补充() throws Exception {
        long jointId = decidedReadyJoint("CC-4");
        long batchId = jdbc.queryForObject("SELECT batch_id FROM joint_order WHERE id = ?",
                Long.class, jointId);
        // 线程组 A：复测毒死蜱（升项版本）；线程组 B：携带旧项版本判定
        List<Outcome<Object>> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Outcome<Object>> retest = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return new Outcome<>(jointService.addResult(jointId, new JointResultRequest(
                            "JR-C4-RE", 4, "毒死蜱", "SN-CC-4-1", new BigDecimal("0.03"), T0)), null);
                } catch (ApiException e) {
                    return new Outcome<>(null, e);
                }
            });
            Future<Outcome<Object>> decide = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return new Outcome<>(jointService.decide(jointId, new JointDecisionRequest(
                            "JD-C4", 4, List.of(new JointItemVersionEntry("毒死蜱", 1),
                                    new JointItemVersionEntry("克百威", 1)))), null);
                } catch (ApiException e) {
                    return new Outcome<>(null, e);
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            outcomes.add(retest.get(20, TimeUnit.SECONDS));
            outcomes.add(decide.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        Integer decisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_decision WHERE joint_id = ?", Integer.class, jointId);
        Integer retestVersions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_result WHERE joint_id = ? AND item = '毒死蜱'",
                Integer.class, jointId);
        String batchStatus = jdbc.queryForObject("SELECT status FROM batch WHERE id = ?",
                String.class, batchId);
        if (decisions == 1) {
            // 判定先生效：携带 v1 判定合格放行，复测只记补充信息（项版本不变）
            assertThat(retestVersions).isEqualTo(1);
            assertThat(batchStatus).isEqualTo("RELEASED");
        } else {
            // 复测先生效：判定版本失配零写入，批次仍冻结
            assertThat(decisions).isZero();
            assertThat(retestVersions).isEqualTo(2);
            assertThat(batchStatus).isEqualTo("FROZEN");
        }
    }

    @Test
    void 并发判定同请求号仅一次判定其余重放() throws Exception {
        long jointId = decidedReadyJoint("CC-5");
        JointDecisionRequest same = new JointDecisionRequest("JD-C5", 4,
                List.of(new JointItemVersionEntry("毒死蜱", 1), new JointItemVersionEntry("克百威", 1)));
        List<Outcome<Object>> outcomes = runConcurrently(THREADS, i ->
                () -> jointService.decide(jointId, same));
        long ok = outcomes.stream().filter(o -> o.error() == null).count();
        // 一次真实判定 + 同请求号重放全部成功
        assertThat(ok).as("结果明细: %s", outcomes.stream()
                .map(o -> o.error() == null ? "ok"
                        : o.error().status().value() + ":" + o.error().getMessage())
                .toList()).isEqualTo(THREADS);
        long replayed = outcomes.stream()
                .filter(o -> o.error() == null
                        && ((com.example.starter.pesticide.dto.ApiDtos.JointDecisionResponse) o.value()).replayed())
                .count();
        assertThat(replayed).isEqualTo(THREADS - 1);
        Integer decisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_decision WHERE joint_id = ?", Integer.class, jointId);
        assertThat(decisions).isEqualTo(1);
        Integer admissions = jdbc.queryForObject("SELECT COUNT(*) FROM admission", Integer.class);
        assertThat(admissions).isEqualTo(1);
        // 判定后异请求号重复判定：422 不重开
        try {
            jointService.decide(jointId, new JointDecisionRequest("JD-C5-OTHER", 5,
                    List.of(new JointItemVersionEntry("毒死蜱", 1),
                            new JointItemVersionEntry("克百威", 1))));
            org.assertj.core.api.Assertions.fail("应拒绝重复判定");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(422);
        }
    }

    /**
     * 构造疑似冻结批次并返回快检记录 ID。
     */
    private long frozenBatch(String suffix) {
        var report = batchService.report(new ReportRequest("R-" + suffix, "山东寿光", "番茄",
                "B-" + suffix, new BigDecimal("3")));
        batchService.sample(report.batchId(), "有机磷");
        return testService.rapidTest(new TestRequest("T-" + suffix, report.batchId(), "有机磷",
                new BigDecimal("60"))).testId();
    }

    /**
     * 构造已创建联检单（OPEN，版本 1）。
     */
    private long openJoint(String suffix) {
        long testId = frozenBatch(suffix);
        long batchId = jdbc.queryForObject("SELECT id FROM batch WHERE batch_no = ?",
                Long.class, "B-" + suffix);
        return jointService.create(new JointCreateRequest("JC-" + suffix, batchId, testId,
                "LAB-A", List.of("毒死蜱", "克百威"))).jointId();
    }

    /**
     * 构造已分样联检单（ALLOCATED，版本 2）。
     */
    private long allocatedJoint(String suffix) {
        long jointId = openJoint(suffix);
        jointService.allocate(jointId, new JointAllocateRequest("JA-" + suffix, 1,
                List.of(new JointAllocEntry("毒死蜱", "SN-" + suffix + "-1"),
                        new JointAllocEntry("克百威", "SN-" + suffix + "-2"))));
        return jointId;
    }

    /**
     * 构造两项结果均为 v1 的待判定联检单（版本 4）。
     */
    private long decidedReadyJoint(String suffix) {
        long jointId = allocatedJoint(suffix);
        jointService.addResult(jointId, new JointResultRequest("JR-" + suffix + "-1", 2,
                "毒死蜱", "SN-" + suffix + "-1", new BigDecimal("0.01"), T0));
        jointService.addResult(jointId, new JointResultRequest("JR-" + suffix + "-2", 3,
                "克百威", "SN-" + suffix + "-2", new BigDecimal("0.01"), T0));
        return jointId;
    }

    private <T> List<Outcome<T>> runConcurrently(int threads, SupplierFactory<T> factory) throws Exception {
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
