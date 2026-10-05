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

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 20, 2, 0);
    private static final LocalDateTime PUBLISH_AT = LocalDateTime.of(2026, 11, 19, 23, 0);

    private static final String DEV1 = "DEV-CORE-SW-01";
    private static final String DEV2 = "DEV-CORE-SW-02";

    @Autowired
    private CutoverService cutoverService;
    @Autowired
    private BatchService batchService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cutover_block_record");
        jdbc.update("DELETE FROM cutover_batch_edge");
        jdbc.update("DELETE FROM cutover_batch_member");
        jdbc.update("DELETE FROM cutover_batch");
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    private void prepareApproved(String id, String device) {
        cutoverService.create(new CreateCutoverRequest(id, "割接-" + id, device,
                Priority.MEDIUM, BASE, BASE.plusHours(2),
                steps("硬性检查", "HARDCHECK")));
        cutoverService.analyze(id);
        cutoverService.approve(new ApproveRequest("ap-" + id, id, true, null));
    }

    private PublishBatchRequest publishReq(String batchId) {
        return new PublishBatchRequest(batchId, PUBLISH_AT,
                List.of(new BatchMemberRef("R-A", 3), new BatchMemberRef("R-B", 3)),
                List.of(new BatchEdgeRef("R-A", "R-B")));
    }

    private static Object safeGet(Future<?> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    @Test
    @DisplayName("发布与单申请启动并发：只能形成一致顺序（发布先->启动等待；启动先->发布整批拒绝）")
    void publishAndStartRaceHasConsistentOrder() throws Exception {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> fp = pool.submit(() -> {
                start.await();
                return batchService.publish(publishReq("B-RACE"));
                });
            Future<Object> fs = pool.submit(() -> {
                start.await();
                return cutoverService.startExecution(
                        new StartExecutionRequest("ex-R-B", "R-B", BASE));
            });
            start.countDown();

            Object publishResult = safeGet(fp);
            Object startResult = safeGet(fs);

            boolean publishWon = publishResult instanceof BatchActionResult;
            boolean startExecuted = startResult instanceof ActionResult ar
                    && "EXECUTING".equals(ar.request().status());

            // 一致顺序：恰好一种结局，不允许既发布成功又让成员绕过门禁启动
            assertThat(publishWon).isNotEqualTo(startExecuted);
            if (publishWon) {
                // 发布先：启动被门禁拦下返回等待，成员仍为 APPROVED
                assertThat(startResult).isInstanceOf(ActionResult.class);
                assertThat(((ActionResult) startResult).message()).contains("等待");
                assertThat(cutoverService.get("R-B").status()).isEqualTo("APPROVED");
                assertThat(batchService.getBatch("B-RACE").status()).isEqualTo("PUBLISHED");
            } else {
                // 启动先：发布看到成员已开始，整批拒绝(422)，批次不落库
                assertThat(publishResult).isInstanceOf(BusinessRuleException.class);
                assertThat(cutoverService.get("R-B").status()).isEqualTo("EXECUTING");
                assertThat(cutoverService.get("R-B").batchId()).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("前置失败与后继启动并发：终态必为依赖阻断，启动方要么等待要么被拒绝，不得启动")
    void predecessorFailureAndSuccessorStartRace() throws Exception {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        batchService.publish(publishReq("B-FAIL"));
        cutoverService.startExecution(new StartExecutionRequest("ex-R-A", "R-A", BASE));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // A：前置硬性检查失败 -> 回退并同事务传播阻断
            Future<Object> ff = pool.submit(() -> {
                start.await();
                return cutoverService.report(new StepReportRequest(
                        "r1-R-A", "R-A", 1, false, BASE.plusMinutes(10), null));
            });
            // B：同时尝试启动后继
            Future<Object> fs = pool.submit(() -> {
                start.await();
                return cutoverService.startExecution(
                        new StartExecutionRequest("ex-R-B", "R-B", BASE));
            });
            start.countDown();

            Object failResult = safeGet(ff);
            Object startResult = safeGet(fs);

            assertThat(failResult).isInstanceOf(ActionResult.class);
            assertThat(((ActionResult) failResult).request().status()).isEqualTo("ROLLED_BACK");

            // 启动方只允许两种结局：被门禁拦下等待（随后被传播阻断），或已被阻断而 422
            if (startResult instanceof ActionResult ar) {
                assertThat(ar.message()).contains("等待");
            } else {
                assertThat(startResult).isInstanceOf(BusinessRuleException.class);
            }
            // 无论哪种顺序，最终一致：R-B 必为依赖阻断且留有阻断明细，绝不处于执行中
            assertThat(cutoverService.get("R-B").status()).isEqualTo("DEPENDENCY_BLOCKED");
            assertThat(batchService.listBlocks("B-FAIL")).hasSize(1);
            assertThat(batchService.listBlocks("B-FAIL").get(0).sourceRequestId())
                    .isEqualTo("R-A");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同键并发发布：恰好一次生效，另一次幂等重放首次冻结结果")
    void concurrentSameKeyPublishIsIdempotent() throws Exception {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        PublishBatchRequest same = publishReq("B-IDEM-RACE");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> f1 = pool.submit(() -> {
                start.await();
                return batchService.publish(same);
            });
            Future<Object> f2 = pool.submit(() -> {
                start.await();
                return batchService.publish(same);
            });
            start.countDown();

            Object r1 = safeGet(f1);
            Object r2 = safeGet(f2);
            assertThat(r1).isInstanceOf(BatchActionResult.class);
            assertThat(r2).isInstanceOf(BatchActionResult.class);
            long applied = java.util.stream.Stream.of(r1, r2)
                    .map(r -> (BatchActionResult) r).filter(r -> !r.replayed()).count();
            long replayed = java.util.stream.Stream.of(r1, r2)
                    .map(r -> (BatchActionResult) r).filter(BatchActionResult::replayed).count();
            assertThat(applied).as("恰好一次真正发布").isEqualTo(1);
            assertThat(replayed).as("另一次幂等重放").isEqualTo(1);

            // 批次只有一行，成员各绑定一次
            Integer batchCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cutover_batch WHERE batch_id = 'B-IDEM-RACE'",
                    Integer.class);
            assertThat(batchCount).isEqualTo(1);
            Integer memberCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cutover_batch_member WHERE batch_id = 'B-IDEM-RACE'",
                    Integer.class);
            assertThat(memberCount).isEqualTo(2);
            assertThat(cutoverService.get("R-A").batchId()).isEqualTo("B-IDEM-RACE");
        } finally {
            pool.shutdownNow();
        }
    }
}
