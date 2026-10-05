package com.example.starter.cutover;

import static com.example.starter.cutover.CutoverFixtures.steps;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchEdgeRef;
import com.example.starter.cutover.dto.BatchMemberRef;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.service.BatchService;
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
 * 批次并发边界测试（真实线程 + H2 行锁）：
 * 发布与单申请启动、前置失败与后继启动、同键并发发布只能形成一致顺序。
 */
@SpringBootTest
class BatchConcurrencyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 3, 2, 0);
    private static final LocalDateTime END = BASE.plusHours(2);

    @Autowired
    private CutoverService cutoverService;
    @Autowired
    private BatchService batchService;
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

    private int prepareApproved(String id, String device) {
        cutoverService.create(new CreateCutoverRequest(id, "割接-" + id, device,
                Priority.MEDIUM, BASE, END,
                steps("停流量", "NORMAL", "硬性检查", "HARDCHECK")));
        cutoverService.analyze(id);
        cutoverService.approve(new ApproveRequest("ap-" + id, id, true, null));
        return cutoverService.get(id).version();
    }

    private PublishBatchRequest publishReq(String batchId, List<BatchMemberRef> members,
                                           List<BatchEdgeRef> edges) {
        return new PublishBatchRequest(batchId, "批次-" + batchId, BASE, members, edges);
    }

    @Test
    @DisplayName("同键并发发布：恰好一次生效，另一次幂等重放，批次只落一行")
    void concurrentPublishSameKeyIsIdempotent() throws Exception {
        int va = prepareApproved("BC-A", "DEV-DB-01");
        PublishBatchRequest same = publishReq("BAT-C1",
                List.of(new BatchMemberRef("BC-A", va)), List.of());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<BatchActionResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return batchService.publish(same);
                }));
            }
            start.countDown();
            BatchActionResult r1 = futures.get(0).get(30, TimeUnit.SECONDS);
            BatchActionResult r2 = futures.get(1).get(30, TimeUnit.SECONDS);

            long applied = java.util.stream.Stream.of(r1, r2)
                    .filter(r -> !r.replayed()).count();
            long replayed = java.util.stream.Stream.of(r1, r2)
                    .filter(BatchActionResult::replayed).count();
            assertThat(applied).as("恰好一次真正发布").isEqualTo(1);
            assertThat(replayed).as("另一次幂等重放").isEqualTo(1);

            Integer batches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cutover_batch WHERE batch_id = 'BAT-C1'",
                    Integer.class);
            assertThat(batches).isEqualTo(1);
            Integer members = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cutover_batch_member WHERE batch_id = 'BAT-C1'",
                    Integer.class);
            assertThat(members).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("发布与单申请启动并发：只形成一致顺序（启动必先提交则发布422，发布先则启动放行并置STARTED）")
    void publishVsStartRaceIsConsistent() throws Exception {
        int vx = prepareApproved("BC-X", "DEV-DB-01");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> publishFuture = pool.submit(() -> {
                start.await();
                return batchService.publish(publishReq("BAT-C2",
                        List.of(new BatchMemberRef("BC-X", vx)), List.of()));
            });
            Future<Object> startFuture = pool.submit(() -> {
                start.await();
                return cutoverService.startExecution(
                        new StartExecutionRequest("ex-BC-X", "BC-X", BASE));
            });
            start.countDown();

            Object publishOutcome = outcome(publishFuture);
            Object startOutcome = outcome(startFuture);

            // 无前置成员：无论顺序如何，启动都必须成功（发布先则门禁放行，启动先则不受批次影响）
            assertThat(startOutcome).isInstanceOf(ActionResult.class);
            assertThat(((ActionResult) startOutcome).request().status()).isEqualTo("EXECUTING");

            if (publishOutcome instanceof BatchActionResult published) {
                // 发布先提交：启动随后放行，成员被置为 STARTED
                assertThat(published.batch().status()).isEqualTo("PUBLISHED");
                assertThat(batchService.get("BAT-C2").members().get(0).memberStatus())
                        .isEqualTo("STARTED");
            } else {
                // 启动先提交：版本/状态已变化，发布整批拒绝(422)
                assertThat(publishOutcome).isInstanceOf(BusinessRuleException.class);
                Integer batches = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM cutover_batch WHERE batch_id = 'BAT-C2'",
                        Integer.class);
                assertThat(batches).isZero();
            }
            assertThat(cutoverService.get("BC-X").status()).isEqualTo("EXECUTING");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("前置失败与后继启动并发：后继要么等待要么被阻断，绝不放行；最终一致阻断")
    void predecessorFailVsSuccessorStartRace() throws Exception {
        int va = prepareApproved("BC-A", "DEV-DB-01");
        int vb = prepareApproved("BC-B", "DEV-FW-01");
        batchService.publish(publishReq("BAT-C3",
                List.of(new BatchMemberRef("BC-A", va), new BatchMemberRef("BC-B", vb)),
                List.of(new BatchEdgeRef("BC-A", "BC-B"))));
        // 前置进入执行
        cutoverService.startExecution(new StartExecutionRequest("ex-BC-A", "BC-A", BASE));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // A：前置第一步失败 -> 回退 + 同事务阻断传播
            Future<Object> failFuture = pool.submit(() -> {
                start.await();
                return cutoverService.report(new StepReportRequest(
                        "rp-BC-A-1", "BC-A", 1, false, BASE.plusMinutes(5), null));
            });
            // B：后继尝试启动
            Future<Object> startFuture = pool.submit(() -> {
                start.await();
                return cutoverService.startExecution(
                        new StartExecutionRequest("ex-BC-B", "BC-B", BASE));
            });
            start.countDown();

            Object failOutcome = outcome(failFuture);
            Object startOutcome = outcome(startFuture);

            assertThat(failOutcome).isInstanceOf(ActionResult.class);
            // 后继启动只有两种一致结果：等待（前置仍执行中）或 422（已被依赖阻断）
            if (startOutcome instanceof ActionResult waited) {
                assertThat(waited.message()).contains("等待");
                assertThat(waited.request().status()).isEqualTo("APPROVED");
            } else {
                assertThat(startOutcome).isInstanceOf(BusinessRuleException.class);
                assertThat(((BusinessRuleException) startOutcome).getMessage())
                        .contains("依赖阻断");
            }

            // 最终状态一致：前置回退终态，后继阻断且未开始、无步骤写入
            assertThat(cutoverService.get("BC-A").status()).isEqualTo("ROLLED_BACK");
            assertThat(batchService.get("BAT-C3").members().get(1).memberStatus())
                    .isEqualTo("BLOCKED");
            assertThat(batchService.get("BAT-C3").members().get(1).blockSources())
                    .containsExactly("BC-A");
            assertThat(cutoverService.get("BC-B").status()).isEqualTo("APPROVED");
            assertThat(cutoverService.events("BC-B"))
                    .noneMatch(e -> "EXECUTE_START".equals(e.eventType()));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 取并发结果：正常返回结果对象，业务异常返回异常对象本身。 */
    private static Object outcome(Future<Object> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
