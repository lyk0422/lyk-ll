package com.example.starter.cutover;

import static com.example.starter.cutover.CutoverFixtures.DEVICE_SW1;
import static com.example.starter.cutover.CutoverFixtures.steps;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.ExpireCheckRequest;
import com.example.starter.cutover.dto.SealRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.model.CutoverEventPO;
import com.example.starter.cutover.repo.CutoverRepository;
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
 * H2 真实并发与事务边界测试：行锁 FOR UPDATE 串行化下的唯一终态与幂等。
 */
@SpringBootTest
class CutoverConcurrencyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 2, 0);

    @Autowired
    private CutoverService service;
    @Autowired
    private CutoverRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    private void prepareExecuting(String id, LocalDateTime s, LocalDateTime e, Priority p) {
        service.create(new CreateCutoverRequest(id, "割接-" + id, DEVICE_SW1, p, s, e,
                steps("停流量", "NORMAL", "配置下发", "NORMAL",
                        "关键验证", "CRITICAL", "硬性检查", "HARDCHECK")));
        service.analyze(id);
        service.approve(new ApproveRequest("ap-" + id, id, true, null));
        service.startExecution(new StartExecutionRequest("ex-" + id, id, s));
    }

    @Test
    @DisplayName("回退与验证通过并发：终态唯一，不得既回退又封板（真实线程+行锁）")
    void rollbackAndVerifyRaceHasSingleTerminal() throws Exception {
        LocalDateTime end = BASE.plusHours(1);
        String id = "R-RACE-1";
        prepareExecuting(id, BASE, end, Priority.HIGH);
        // 前三步在窗口内完成，仅剩 seq4(HARDCHECK) 在窗口终点回报
        service.report(new StepReportRequest("c-r1", id, 1, true, BASE.plusMinutes(40), null));
        service.report(new StepReportRequest("c-r2", id, 2, true, BASE.plusMinutes(45), null));
        service.report(new StepReportRequest("c-r3", id, 3, true, BASE.plusMinutes(50), null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // A：恰在窗口终点回报最后一步成功 -> VERIFIED
            Future<Object> fa = pool.submit(() -> {
                start.await();
                return service.report(new StepReportRequest(
                        "c-r4-ok", id, 4, true, end, null));
            });
            // B：窗口结束时刻巡检（恰终点），若仍有未完成步骤 -> WINDOW_EXPIRED 强制回退
            Future<Object> fb = pool.submit(() -> {
                start.await();
                return service.expireCheck(new ExpireCheckRequest("c-exp", id, end));
            });
            start.countDown();

            // 竞态失败方可能抛业务异常（回退后再回报），单独捕获。
            ActionResult aResult = safeGet(fa);
            ActionResult bResult = safeGet(fb);

            String finalStatus = service.get(id).status();
            // 唯一终态：只能是验证通过或窗口超期回退之一
            assertThat(finalStatus).isIn("VERIFIED", "WINDOW_EXPIRED");

            boolean hasRollbackStep = repo.listSteps(id).stream()
                    .anyMatch(s -> "ROLLBACK".equals(s.getStepType()));
            boolean hasRollbackTrigger = service.events(id).stream()
                    .anyMatch(e -> "ROLLBACK_TRIGGER".equals(e.eventType()));

            if ("VERIFIED".equals(finalStatus)) {
                // 验证先到达：不得产生任何回退痕迹
                assertThat(hasRollbackStep).isFalse();
                assertThat(hasRollbackTrigger).isFalse();
                // 封板必须成功
                ActionResult seal = service.seal(new SealRequest("c-seal", id));
                assertThat(seal.request().status()).isEqualTo("SEALED");
            } else {
                // 回退先到达：终态唯一，封板必须被拒绝
                assertThat(hasRollbackStep).isTrue();
                assertThat(hasRollbackTrigger).isTrue();
                assertThatThrownBySeal(id);
            }
            assertThat(bResult).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    private static ActionResult safeGet(Future<Object> future) {
        try {
            return (ActionResult) future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private void assertThatThrownBySeal(String id) {
        try {
            service.seal(new SealRequest("c-seal-fail", id));
            throw new AssertionError("回退终态后封板必须被拒绝");
        } catch (BusinessRuleException expected) {
            assertThat(expected.getMessage()).contains("终态唯一");
        }
    }

    @Test
    @DisplayName("同键并发重放：两个线程用同一 requestUid 回报，恰好一次生效，另一次幂等返回")
    void concurrentSameKeyReportIsIdempotent() throws Exception {
        LocalDateTime end = BASE.plusHours(2);
        String id = "R-RACE-2";
        prepareExecuting(id, BASE, end, Priority.HIGH);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            StepReportRequest same = new StepReportRequest(
                    "same-uid-r1", id, 1, true, BASE.plusMinutes(5), null);
            List<Future<ActionResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.report(same);
                }));
            }
            start.countDown();

            ActionResult first = futures.get(0).get(30, TimeUnit.SECONDS);
            ActionResult second = futures.get(1).get(30, TimeUnit.SECONDS);

            long applied = java.util.stream.Stream.of(first, second)
                    .filter(r -> !r.replayed()).count();
            long replayed = java.util.stream.Stream.of(first, second)
                    .filter(ActionResult::replayed).count();
            assertThat(applied).as("恰好一次真正生效").isEqualTo(1);
            assertThat(replayed).as("另一次幂等重放").isEqualTo(1);

            // 事件只落一条，步骤只有一条成功记录，无重复步骤
            List<CutoverEventPO> events = repo.listEvents(id).stream()
                    .filter(e -> "same-uid-r1".equals(e.getRequestUid())).toList();
            assertThat(events).hasSize(1);
            assertThat(repo.listSteps(id).get(0).getStatus()).isEqualTo("SUCCESS");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("同设备并发申请：半开窗口冲突在真实并发下仍只放行一个（设备行锁串行）")
    void concurrentOverlappingRequestsOnlyOneAccepted() throws Exception {
        LocalDateTime s = BASE.plusDays(1);
        LocalDateTime e = s.plusHours(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ActionResult>> futures = new java.util.ArrayList<>();
            for (String id : new String[]{"R-C1", "R-C2"}) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.create(new CreateCutoverRequest(
                            id, "割接-" + id, DEVICE_SW1, Priority.MEDIUM, s, e,
                            steps("s1", "NORMAL")));
                }));
            }
            start.countDown();

            ActionResult r1 = futures.get(0).get(30, TimeUnit.SECONDS);
            ActionResult r2 = futures.get(1).get(30, TimeUnit.SECONDS);
            long submitted = java.util.stream.Stream.of(r1, r2)
                    .filter(r -> "SUBMITTED".equals(r.request().status())).count();
            long rejected = java.util.stream.Stream.of(r1, r2)
                    .filter(r -> "REJECTED".equals(r.request().status())).count();
            assertThat(submitted).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
