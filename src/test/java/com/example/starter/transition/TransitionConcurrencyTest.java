package com.example.starter.transition;

import com.example.starter.transition.api.ApplyTransitionRequest;
import com.example.starter.transition.api.ExecuteTransitionRequest;
import com.example.starter.transition.api.GradeUpsertRequest;
import com.example.starter.transition.api.InspectionRequest;
import com.example.starter.transition.api.RegisterRollRequest;
import com.example.starter.transition.api.Responses.OrderResponse;
import com.example.starter.transition.domain.BizType;
import com.example.starter.transition.po.TransitionRoll;
import com.example.starter.transition.repo.ResourceRepository;
import com.example.starter.transition.service.GradeService;
import com.example.starter.transition.service.IdempotencyService;
import com.example.starter.transition.service.IdempotencyService.IdempotentResponse;
import com.example.starter.transition.service.RollService;
import com.example.starter.transition.service.TransitionService;
import com.example.starter.transition.web.ConflictException;
import com.example.starter.transition.web.UnprocessableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 事务与并发边界测试：并发唯一受理、同键重放、资源占用原子性、复议并发唯一终态。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paper_test_conc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransitionConcurrencyTest {

    @Autowired
    private TransitionService transitionService;
    @Autowired
    private RollService rollService;
    @Autowired
    private GradeService gradeService;
    @Autowired
    private IdempotencyService idempotencyService;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM late_inspection_event");
        jdbc.update("DELETE FROM roll_judgement");
        jdbc.update("DELETE FROM transition_roll");
        jdbc.update("DELETE FROM cleaning_window");
        jdbc.update("DELETE FROM machine_execution_lock");
        jdbc.update("DELETE FROM transition_order");
        jdbc.update("DELETE FROM request_registry");
        jdbc.update("DELETE FROM grade");
        upsertGrade("G-A", "100.00", 3, "800", "1200", "3.0", "4.5", "30.00");
        upsertGrade("G-B", "110.00", 5, "900", "1100", "3.2", "4.0", "32.00");
        upsertGrade("G-C", "115.00", 3, "900", "1100", "3.2", "4.0", "32.00");
    }

    /** 测试上下文结束后释放命名内存库。 */
    @AfterAll
    void releaseDatabase() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void concurrentApplySameMachineOnlyOneAccepted() throws Exception {
        int threads = 8;
        List<Outcome<Object>> results = runConcurrently(threads, i -> {
            String orderNo = "O-C" + i;
            return OrderResponse.from(transitionService.apply(new ApplyTransitionRequest(
                    "req-c" + i, "PM-X", orderNo, "G-A", "G-B")));
        });

        long succeeded = results.stream().filter(r -> r.error() == null).count();
        long conflicted = results.stream()
                .filter(r -> r.error() instanceof ConflictException).count();
        assertEquals(1, succeeded, "同一纸机并发申请只能唯一受理一单");
        assertEquals(threads - 1, conflicted);
        assertEquals(1, count("transition_order"));
        assertEquals(1, count("machine_execution_lock"));
    }

    @Test
    void concurrentSameRequestIdReplayReturnsFirstResultOnly() throws Exception {
        int threads = 8;
        ApplyTransitionRequest request = new ApplyTransitionRequest(
                "req-same", "PM-Y", "O-SAME", "G-A", "G-B");
        String fingerprint = IdempotencyService.fingerprint("req-same", BizType.APPLY.name(),
                "PM-Y", "O-SAME", "G-A", "G-B");

        List<Outcome<IdempotentResponse>> results = runConcurrently(threads, i ->
                idempotencyService.execute(BizType.APPLY, "req-same", fingerprint,
                        () -> OrderResponse.from(transitionService.apply(request))));

        for (Outcome<IdempotentResponse> r : results) {
            assertNotNull(r.value(), "同键同参并发重放不应失败: " + r.error());
        }
        String firstBody = results.get(0).value().body();
        for (Outcome<IdempotentResponse> r : results) {
            assertEquals(firstBody, r.value().body(), "所有重放必须返回首次结果");
        }
        assertEquals(1, count("transition_order"));
        assertEquals(1, count("request_registry"));
    }

    @Test
    void stopCleanOccupiesCleaningWindowAtomicallyButDirectDoesNot() {
        // G-A 100g → G-C 115g：克重差 15% > 10% → 停机清洗
        transitionService.apply(new ApplyTransitionRequest(
                UUID.randomUUID().toString(), "PM-S", "O-SC", "G-A", "G-C"));
        transitionService.execute(new ExecuteTransitionRequest(
                UUID.randomUUID().toString(), "O-SC", 120,
                new BigDecimal("1000"), new BigDecimal("3.5")));
        assertTrue(resourceRepository.hasCleaningWindow("O-SC"), "停机清洗单必须登记清洗窗口");
        assertEquals("EXECUTING", jdbc.queryForObject(
                "SELECT status FROM transition_order WHERE order_no='O-SC'", String.class));

        // G-A 100g → G-B 110g：恰 10% → 直切，不得占用清洗窗口
        transitionService.apply(new ApplyTransitionRequest(
                UUID.randomUUID().toString(), "PM-D", "O-D", "G-A", "G-B"));
        transitionService.execute(new ExecuteTransitionRequest(
                UUID.randomUUID().toString(), "O-D", 60,
                new BigDecimal("1000"), new BigDecimal("3.5")));
        assertFalse(resourceRepository.hasCleaningWindow("O-D"), "直切单不得占用清洗窗口");
        assertEquals(1, count("cleaning_window"));
    }

    @Test
    void failedExecuteLeavesNoResourceOrStateChange() {
        transitionService.apply(new ApplyTransitionRequest(
                UUID.randomUUID().toString(), "PM-F", "O-F", "G-A", "G-C"));
        // 车速 1200 超出 G-C 窗口上限 1100 → 422
        assertThrows(UnprocessableException.class, () -> transitionService.execute(
                new ExecuteTransitionRequest(UUID.randomUUID().toString(), "O-F", 60,
                        new BigDecimal("1200"), new BigDecimal("3.5"))));
        assertFalse(resourceRepository.hasCleaningWindow("O-F"), "执行失败不得残留清洗窗口");
        assertEquals("APPLIED", jdbc.queryForObject(
                "SELECT status FROM transition_order WHERE order_no='O-F'", String.class));
    }

    @Test
    void concurrentAppealOnlyOneSucceedsWithUniqueFinalState() throws Exception {
        prepareInspectedRoll("PM-A", "O-A", "R-A", true);

        List<Outcome<TransitionRoll>> results = runConcurrently(2, i ->
                rollService.appeal("req-ap" + i, "R-A"));

        long succeeded = results.stream().filter(r -> r.error() == null).count();
        long conflicted = results.stream()
                .filter(r -> r.error() instanceof ConflictException).count();
        assertEquals(1, succeeded, "复议改判只能成功一次");
        assertEquals(1, conflicted);

        TransitionRoll roll = rollService.requireRoll("R-A");
        assertEquals("QUALIFIED", roll.finalGrade);
        assertEquals(1, roll.appealCount);
        assertEquals(2, rollService.judgementChain("R-A").size(), "改判链须保留首次降级与复议改判");
    }

    @Test
    void concurrentInspectAndAppealConvergeToUniqueFinalState() throws Exception {
        prepareRegisteredRoll("PM-B", "O-B", "R-B");

        List<Outcome<Object>> results = runConcurrently(2, i -> {
            if (i == 0) {
                return rollService.inspect(new InspectionRequest("req-i0", "R-B",
                        new BigDecimal("115.50"), 6, new BigDecimal("32.00")));
            }
            return rollService.appeal("req-ap0", "R-B");
        });

        TransitionRoll roll = rollService.requireRoll("R-B");
        int chainSize = rollService.judgementChain("R-B").size();
        if (roll.appealCount == 1) {
            // 复议在首次判定之后生效：唯一终态为改判正品
            assertEquals("QUALIFIED", roll.finalGrade);
            assertEquals("INSPECTED", roll.inspectionStatus);
            assertEquals(2, chainSize);
        } else {
            // 复议先于首次判定到达被拒绝：唯一终态为强制降级
            assertEquals(0, roll.appealCount);
            assertEquals("DOWNGRADED", roll.finalGrade);
            assertEquals("INSPECTED", roll.inspectionStatus);
            assertEquals(1, chainSize);
        }
    }

    private void prepareRegisteredRoll(String machine, String orderNo, String rollNo) {
        transitionService.apply(new ApplyTransitionRequest(
                UUID.randomUUID().toString(), machine, orderNo, "G-A", "G-B"));
        transitionService.execute(new ExecuteTransitionRequest(
                UUID.randomUUID().toString(), orderNo, 90,
                new BigDecimal("1000"), new BigDecimal("3.5")));
        rollService.register(new RegisterRollRequest(
                UUID.randomUUID().toString(), rollNo, orderNo, new BigDecimal("1500")));
    }

    private void prepareInspectedRoll(String machine, String orderNo, String rollNo, boolean passGate) {
        prepareRegisteredRoll(machine, orderNo, rollNo);
        rollService.inspect(new InspectionRequest(UUID.randomUUID().toString(), rollNo,
                new BigDecimal("115.50"), 6,
                passGate ? new BigDecimal("32.00") : new BigDecimal("31.00")));
    }

    private void upsertGrade(String code, String weight, int ash,
                             String speedMin, String speedMax,
                             String consMin, String consMax, String tensile) {
        gradeService.upsert(new GradeUpsertRequest(
                UUID.randomUUID().toString(), code, new BigDecimal(weight), ash,
                new BigDecimal(speedMin), new BigDecimal(speedMax),
                new BigDecimal(consMin), new BigDecimal(consMax), new BigDecimal(tensile)));
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 真实并发执行：所有线程就绪后同时放行，结果带超时回收。
     */
    private <T> List<Outcome<T>> runConcurrently(int threads, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发放行超时");
                }
                try {
                    return new Outcome<>(task.apply(index), null);
                } catch (RuntimeException e) {
                    return new Outcome<>(null, e);
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
        start.countDown();
        List<Outcome<T>> results = new ArrayList<>();
        for (Future<Outcome<T>> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    /** 并发任务结果：成功值或捕获的业务异常。 */
    private record Outcome<T>(T value, RuntimeException error) {
    }
}
