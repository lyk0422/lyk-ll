package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointTestCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestCreateResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeItem;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestResultResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitItem;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitResponse;
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
 * 定量联检并发与事务边界测试：并发创建唯一受理、并发分样版本门控、
 * 并发复测致版本失配则判定零写入、同键并发重放一致。
 */
@SpringBootTest
class JointTestConcurrencyTest {

    private static final int THREADS = 8;
    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    private BatchService batchService;

    @Autowired
    private TestService testService;

    @Autowired
    private JointTestService jointTestService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM joint_test_judgment");
        jdbc.update("DELETE FROM joint_test_result");
        jdbc.update("DELETE FROM joint_test_split");
        jdbc.update("DELETE FROM joint_test_item");
        jdbc.update("DELETE FROM joint_test_order");
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
    void 并发创建同批次联检单仅一单受理() throws Exception {
        long batchId = frozenBatch("CJ-1", "CTJ-1");
        List<Outcome<JointTestCreateResponse>> outcomes = runConcurrently(i ->
                () -> jointTestService.create(new JointTestCreateRequest(
                        "CO-" + i, batchId, "LAB-A", List.of("有机磷", "克百威"), T0)));
        long success = outcomes.stream().filter(o -> o.error() == null).count();
        assertThat(success).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o.error() != null).count())
                .isEqualTo(THREADS - 1);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM joint_test_order", Integer.class);
        assertThat(orders).isEqualTo(1);
        Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM joint_test_item", Integer.class);
        assertThat(items).isEqualTo(2);
    }

    @Test
    void 并发同键创建重放一致() throws Exception {
        long batchId = frozenBatch("CJ-2", "CTJ-2");
        JointTestCreateRequest request = new JointTestCreateRequest("CO-SAME", batchId, "LAB-A",
                List.of("有机磷", "克百威"), T0);
        List<Outcome<JointTestCreateResponse>> outcomes = runConcurrently(i -> () ->
                jointTestService.create(request));
        assertThat(outcomes).allMatch(o -> o.error() == null);
        assertThat(outcomes.stream().map(o -> o.value().orderId()).distinct().count()).isEqualTo(1);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM joint_test_order", Integer.class);
        assertThat(orders).isEqualTo(1);
    }

    @Test
    void 并发分样仅一次成功且样本号唯一() throws Exception {
        long batchId = frozenBatch("CJ-3", "CTJ-3");
        long orderId = createOrder(batchId, "CO-3");
        List<Outcome<JointTestSplitResponse>> outcomes = runConcurrently(i ->
                () -> jointTestService.split(orderId, new JointTestSplitRequest("CS-" + i, 1,
                        List.of(new JointTestSplitItem("有机磷", "CSN-" + i + "-1"),
                                new JointTestSplitItem("克百威", "CSN-" + i + "-2")), T0)));
        long success = outcomes.stream().filter(o -> o.error() == null).count();
        assertThat(success).isEqualTo(1);
        // 其余线程因版本失配（409）或已分样（422）失败，整单只有一组分样
        Integer splits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_test_split WHERE order_id = ?", Integer.class, orderId);
        assertThat(splits).isEqualTo(2);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM joint_test_order WHERE id = ?", Integer.class, orderId);
        assertThat(version).isEqualTo(2);
    }

    @Test
    void 并发复测与综合判定版本失配则判定零写入() throws Exception {
        long batchId = frozenBatch("CJ-4", "CTJ-4");
        long orderId = createOrder(batchId, "CO-4");
        split(orderId, "CS-4", "CSN-4-1", "CSN-4-2");
        submit(orderId, "CR-4A", 2, "有机磷", "0.01");
        submit(orderId, "CR-4B", 3, "克百威", "0.01");
        // 当前联检版本 4、两项结果版本均为 1：一半线程复测有机磷，一半线程按旧版本判定
        List<Outcome<Object>> outcomes = runConcurrently(i -> () -> {
            if (i % 2 == 0) {
                return jointTestService.submitResult(orderId, new JointTestResultRequest(
                        "CR-4C-" + i, 4, "有机磷", new BigDecimal("0.02"), T0));
            }
            return jointTestService.judge(orderId, new JointTestJudgeRequest("CJ-4-" + i, 4,
                    List.of(new JointTestJudgeItem("有机磷", 1),
                            new JointTestJudgeItem("克百威", 1)), T0));
        });
        long retestSuccess = outcomes.stream()
                .filter(o -> o.error() == null && o.value() instanceof JointTestResultResponse)
                .count();
        long judgeSuccess = outcomes.stream()
                .filter(o -> o.error() == null && o.value() instanceof JointTestJudgeResponse)
                .count();
        // 版本门控下：至多一类操作基于版本 4 成功一次；其余全部版本失配
        assertThat(retestSuccess + judgeSuccess).isEqualTo(1);
        Integer judgments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_test_judgment WHERE order_id = ?", Integer.class,
                orderId);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM joint_test_order WHERE id = ?", Integer.class, orderId);
        String batchStatus = jdbc.queryForObject(
                "SELECT status FROM batch WHERE id = ?", String.class, batchId);
        if (judgeSuccess == 1) {
            // 判定成功：复测全部版本失配，判定唯一且批次已放行
            assertThat(judgments).isEqualTo(1);
            assertThat(version).isEqualTo(5);
            assertThat(batchStatus).isEqualTo("RELEASED");
        } else {
            // 复测成功：判定全部版本失配零写入，无判定记录、批次仍冻结
            assertThat(judgments).isEqualTo(0);
            assertThat(version).isEqualTo(5);
            assertThat(batchStatus).isEqualTo("FROZEN");
        }
    }

    @Test
    void 并发同键结果提交重放一致仅一条结果() throws Exception {
        long batchId = frozenBatch("CJ-5", "CTJ-5");
        long orderId = createOrder(batchId, "CO-5");
        split(orderId, "CS-5", "CSN-5-1", "CSN-5-2");
        JointTestResultRequest request = new JointTestResultRequest("CR-5", 2, "有机磷",
                new BigDecimal("0.01"), T0);
        List<Outcome<JointTestResultResponse>> outcomes = runConcurrently(i -> () ->
                jointTestService.submitResult(orderId, request));
        assertThat(outcomes).allMatch(o -> o.error() == null);
        assertThat(outcomes.stream().map(o -> o.value().resultId()).distinct().count())
                .isEqualTo(1);
        Integer results = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_test_result WHERE order_id = ?", Integer.class, orderId);
        assertThat(results).isEqualTo(1);
    }

    private long frozenBatch(String reportReqId, String testReqId) {
        var report = batchService.report(new ReportRequest(reportReqId, "山东寿光", "番茄",
                "B-" + reportReqId, new BigDecimal("3")));
        batchService.sample(report.batchId(), "有机磷");
        testService.rapidTest(new TestRequest(testReqId, report.batchId(), "有机磷",
                new BigDecimal("60")));
        return report.batchId();
    }

    private long createOrder(long batchId, String requestId) {
        return jointTestService.create(new JointTestCreateRequest(requestId, batchId, "LAB-A",
                List.of("有机磷", "克百威"), T0)).orderId();
    }

    private void split(long orderId, String requestId, String sampleNo1, String sampleNo2) {
        jointTestService.split(orderId, new JointTestSplitRequest(requestId, 1,
                List.of(new JointTestSplitItem("有机磷", sampleNo1),
                        new JointTestSplitItem("克百威", sampleNo2)), T0));
    }

    private void submit(long orderId, String requestId, int expectedVersion, String item,
                        String concentration) {
        jointTestService.submitResult(orderId, new JointTestResultRequest(requestId,
                expectedVersion, item, new BigDecimal(concentration), T0));
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
    }
}
