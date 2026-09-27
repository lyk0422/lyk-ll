package com.example.starter.cutover;

import static com.example.starter.cutover.CutoverFixtures.steps;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchMemberInput;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.DependencyEdgeInput;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.repo.CutoverRepository;
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
 * 批次 H2 真实并发测试：发布与单申请启动、前置失败与后继启动在同一组行锁下只能形成
 * 一致顺序；同键并发发布恰好一次生效。
 */
@SpringBootTest
class BatchConcurrencyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 15, 2, 0);

    private static final String DEV1 = "DEV-CORE-SW-01";
    private static final String DEV2 = "DEV-CORE-SW-02";

    @Autowired
    private CutoverService cutover;
    @Autowired
    private BatchService batches;
    @Autowired
    private CutoverRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cutover_block");
        jdbc.update("DELETE FROM cutover_batch_edge");
        jdbc.update("DELETE FROM cutover_batch_member");
        jdbc.update("DELETE FROM cutover_batch");
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    private void prepareApproved(String id, String device) {
        cutover.create(new CreateCutoverRequest(id, "割接-" + id, device, Priority.MEDIUM,
                BASE, BASE.plusHours(2),
                steps("停流量", "NORMAL", "硬性检查", "HARDCHECK")));
        cutover.analyze(id);
        cutover.approve(new ApproveRequest("ap-" + id, id, true, null));
    }

    private PublishBatchRequest singleMemberBatch(String batchId, String id) {
        long version = cutover.get(id).version();
        return new PublishBatchRequest(batchId, BASE,
                List.of(new BatchMemberInput(id, version)), List.of());
    }

    /** 取线程结果：正常返回值或抛出的异常，二选一。 */
    private record Outcome(Object value, Throwable error) {
        boolean failed() { return error != null; }
    }

    private Outcome run(Future<Outcome> f) throws Exception {
        return f.get(30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("发布与单申请启动并发：只能形成“先发布后启动(过门禁)”或“先启动后发布(422)”一致顺序")
    void publishVsStartConsistentOrdering() throws Exception {
        prepareApproved("M", DEV1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> fp = pool.submit(() -> {
                start.await();
                try {
                    return new Outcome(batches.publish(singleMemberBatch("B-RACE", "M")), null);
                } catch (Throwable t) {
                    return new Outcome(null, t);
                }
            });
            Future<Outcome> fs = pool.submit(() -> {
                start.await();
                try {
                    return new Outcome(cutover.startExecution(
                            new StartExecutionRequest("ex-M", "M", BASE)), null);
                } catch (Throwable t) {
                    return new Outcome(null, t);
                }
            });
            start.countDown();
            Outcome pub = run(fp);
            Outcome sta = run(fs);

            boolean batchExists = repo.findBatch("B-RACE") != null;
            String finalStatus = cutover.get("M").status();

            if (batchExists) {
                // 发布先提交：成员绑定批次；随后启动无前置，门禁直接满足 -> EXECUTING
                assertThat(pub.failed()).isFalse();
                assertThat(((BatchActionResult) pub.value()).replayed()).isFalse();
                assertThat(cutover.get("M").openBatchId()).isEqualTo("B-RACE");
                assertThat(sta.failed()).isFalse();
                assertThat(((ActionResult) sta.value()).waiting()).isFalse();
                assertThat(finalStatus).isEqualTo("EXECUTING");
            } else {
                // 启动先提交：M 已 EXECUTING（版本已变），发布整批拒绝 422
                assertThat(pub.failed()).isTrue();
                assertThat(pub.error()).isInstanceOf(BusinessRuleException.class);
                assertThat(cutover.get("M").openBatchId()).isNull();
                assertThat(sta.failed()).isFalse();
                assertThat(finalStatus).isEqualTo("EXECUTING");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("前置失败与后继启动并发：后继永不执行，最终一致为依赖阻断（等待或422其一）")
    void predecessorFailureVsSuccessorStartConsistent() throws Exception {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        long va = cutover.get("A").version();
        long vb = cutover.get("B").version();
        batches.publish(new PublishBatchRequest("B-GATE-RACE", BASE,
                List.of(new BatchMemberInput("A", va), new BatchMemberInput("B", vb)),
                List.of(new DependencyEdgeInput("A", "B"))));
        // A 先进入执行
        cutover.startExecution(new StartExecutionRequest("ex-A", "A", BASE));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> ff = pool.submit(() -> {
                start.await();
                try {
                    return new Outcome(cutover.report(new StepReportRequest(
                            "r-A-fail", "A", 1, false, BASE.plusMinutes(5), null)), null);
                } catch (Throwable t) {
                    return new Outcome(null, t);
                }
            });
            Future<Outcome> fs = pool.submit(() -> {
                start.await();
                try {
                    return new Outcome(cutover.startExecution(
                            new StartExecutionRequest("ex-B", "B", BASE)), null);
                } catch (Throwable t) {
                    return new Outcome(null, t);
                }
            });
            start.countDown();
            Outcome fail = run(ff);
            Outcome sta = run(fs);

            // 失败回报必然生效，A 回退
            assertThat(fail.failed()).isFalse();
            assertThat(cutover.get("A").status()).isEqualTo("ROLLED_BACK");

            String bStatus = cutover.get("B").status();
            // 两种一致顺序：失败先提交->B 已阻断，启动 422；启动先到->门禁等待，随后被阻断
            assertThat(bStatus).isEqualTo("DEPENDENCY_BLOCKED");
            if (sta.failed()) {
                assertThat(sta.error()).isInstanceOf(BusinessRuleException.class);
            } else {
                assertThat(((ActionResult) sta.value()).waiting()).isTrue();
            }
            // B 从未真正进入执行，计划步骤保持 PENDING
            assertThat(repo.listSteps("B"))
                    .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo("PENDING"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同键并发发布：恰好一次真正冻结，另一次幂等重放，成员版本只递增一次")
    void concurrentSameKeyPublishIdempotent() throws Exception {
        prepareApproved("S", DEV1);
        PublishBatchRequest req = singleMemberBatch("B-IDEM-RACE", "S");
        long before = cutover.get("S").version();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return new Outcome(batches.publish(req), null);
                    } catch (Throwable t) {
                        return new Outcome(null, t);
                    }
                }));
            }
            start.countDown();
            Outcome o1 = run(futures.get(0));
            Outcome o2 = run(futures.get(1));
            assertThat(o1.failed()).isFalse();
            assertThat(o2.failed()).isFalse();

            long applied = java.util.stream.Stream.of(o1, o2)
                    .filter(o -> !((BatchActionResult) o.value()).replayed()).count();
            long replayed = java.util.stream.Stream.of(o1, o2)
                    .filter(o -> ((BatchActionResult) o.value()).replayed()).count();
            assertThat(applied).isEqualTo(1);
            assertThat(replayed).isEqualTo(1);

            // 成员版本恰好 +1，冻结成员行只有一条
            assertThat(cutover.get("S").version()).isEqualTo(before + 1);
            assertThat(repo.listBatchMembers("B-IDEM-RACE")).hasSize(1);
            assertThat(repo.listBatchEdges("B-IDEM-RACE")).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }
}
