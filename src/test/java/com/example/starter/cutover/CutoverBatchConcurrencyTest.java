package com.example.starter.cutover;

import static com.example.starter.cutover.CutoverFixtures.steps;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.BatchEdgeRef;
import com.example.starter.cutover.dto.BatchMemberRef;
import com.example.starter.cutover.dto.BatchResult;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.service.CutoverBatchService;
import com.example.starter.cutover.service.CutoverService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 依赖批次 H2 真实并发测试：发布与单申请启动、前置失败与后继启动只形成一致顺序。
 */
@SpringBootTest
class CutoverBatchConcurrencyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 8, 2, 0);
    private static final LocalDateTime END = BASE.plusHours(2);
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 11, 7, 20, 0);

    @Autowired
    private CutoverService service;
    @Autowired
    private CutoverBatchService batchService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cutover_batch_edge");
        jdbc.update("DELETE FROM cutover_batch_member");
        jdbc.update("DELETE FROM cutover_batch");
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    private void prepareApproved(String id, String device) {
        service.create(new CreateCutoverRequest(id, "割接-" + id, device,
                Priority.MEDIUM, BASE, END,
                steps("停流量", "NORMAL", "硬性检查", "HARDCHECK")));
        service.analyze(id);
        service.approve(new ApproveRequest("ap-" + id, id, true, null));
    }

    private BatchMemberRef mem(String id) {
        return new BatchMemberRef(id, service.get(id).version());
    }

    @Test
    @DisplayName("前置失败与后继启动并发：后继绝不进入执行，终态为依赖阻断（一致顺序）")
    void prerequisiteFailureVsSuccessorStart() throws Exception {
        prepareApproved("C-A", "DEV-CORE-SW-01");
        prepareApproved("C-B", "DEV-CORE-SW-02");
        batchService.publish(new PublishBatchRequest("BAT-C1", PUBLISHED_AT,
                List.of(mem("C-A"), mem("C-B")),
                List.of(new BatchEdgeRef("C-A", "C-B"))));
        // 前置进入执行并完成首步，仅剩硬性检查
        service.startExecution(new StartExecutionRequest("ex-C-A", "C-A", BASE));
        service.report(new StepReportRequest("r-C-A1", "C-A", 1, true,
                BASE.plusMinutes(10), null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // A：前置硬性检查失败 -> 回退 + 同事务传播阻断
            Future<Object> fail = pool.submit(() -> {
                start.await();
                return service.report(new StepReportRequest("r-C-A2", "C-A", 2,
                        false, BASE.plusMinutes(20), null));
            });
            // B：后继并发启动 -> 只能得到“等待”或“已阻断 422”，绝不放行
            Future<Object> begin = pool.submit(() -> {
                start.await();
                return service.startExecution(
                        new StartExecutionRequest("ex-C-B", "C-B", BASE.plusMinutes(30)));
            });
            start.countDown();

            Object failResult = getQuietly(fail);
            Object beginResult = getQuietly(begin);
            assertThat(failResult).isInstanceOf(ActionResult.class);

            // 后继启动结果只可能是：门禁等待 / 已阻断业务拒绝
            if (beginResult instanceof ActionResult ar) {
                assertThat(ar.waiting()).isTrue();
            } else {
                assertThat(beginResult).isInstanceOf(BusinessRuleException.class);
            }
            // 终态一致：前置回退，后继依赖阻断，绝不 EXECUTING，步骤保持 PENDING
            assertThat(service.get("C-A").status()).isEqualTo("ROLLED_BACK");
            assertThat(service.get("C-B").status()).isEqualTo("DEPENDENCY_BLOCKED");
            assertThat(service.get("C-B").status()).isNotEqualTo("EXECUTING");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("发布与单申请启动并发：只形成一致顺序（先启动则发布拒绝，先发布则启动受门禁）")
    void publishVsSingleStartConsistentOrder() throws Exception {
        prepareApproved("C-R", "DEV-CORE-SW-01");
        PublishBatchRequest publishReq = new PublishBatchRequest("BAT-C2", PUBLISHED_AT,
                List.of(mem("C-R")), List.of());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> publish = pool.submit(() -> {
                start.await();
                return batchService.publish(publishReq);
            });
            Future<Object> begin = pool.submit(() -> {
                start.await();
                return service.startExecution(
                        new StartExecutionRequest("ex-C-R", "C-R", BASE));
            });
            start.countDown();

            Object publishResult = getQuietly(publish);
            Object beginResult = getQuietly(begin);

            String finalStatus = service.get("C-R").status();
            String batchId = service.get("C-R").batchId();
            if (publishResult instanceof BatchResult) {
                // 先发布：绑定批次；单申请启动受门禁（本批无前置 -> 放行执行）
                assertThat(batchId).isEqualTo("BAT-C2");
                assertThat(finalStatus).isEqualTo("EXECUTING");
                assertThat(beginResult).isInstanceOf(ActionResult.class);
            } else {
                // 先启动：申请已 EXECUTING，发布整批拒绝（422）
                assertThat(publishResult).isInstanceOf(BusinessRuleException.class);
                assertThat(batchId).isNull();
                assertThat(finalStatus).isEqualTo("EXECUTING");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同键并发发布：恰好一次冻结生效，另一次幂等重放，成员只绑定一次")
    void concurrentSameKeyPublishIsIdempotent() throws Exception {
        prepareApproved("C-X", "DEV-CORE-SW-01");
        prepareApproved("C-Y", "DEV-CORE-SW-02");
        PublishBatchRequest req = new PublishBatchRequest("BAT-C3", PUBLISHED_AT,
                List.of(mem("C-X"), mem("C-Y")),
                List.of(new BatchEdgeRef("C-X", "C-Y")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<BatchResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return batchService.publish(req);
                }));
            }
            start.countDown();

            BatchResult first = futures.get(0).get(30, TimeUnit.SECONDS);
            BatchResult second = futures.get(1).get(30, TimeUnit.SECONDS);
            long applied = java.util.stream.Stream.of(first, second)
                    .filter(r -> !r.replayed()).count();
            long replayed = java.util.stream.Stream.of(first, second)
                    .filter(BatchResult::replayed).count();
            assertThat(applied).as("恰好一次真正发布").isEqualTo(1);
            assertThat(replayed).as("另一次幂等重放").isEqualTo(1);

            // 成员只绑定一次：版本只加 1
            assertThat(service.get("C-X").batchId()).isEqualTo("BAT-C3");
            Integer memberRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cutover_batch_member WHERE batch_id = 'BAT-C3'",
                    Integer.class);
            assertThat(memberRows).isEqualTo(2);
            Integer bindEvents = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cutover_event WHERE event_type = 'BATCH_BIND'",
                    Integer.class);
            assertThat(bindEvents).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object getQuietly(Future<?> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }
}
