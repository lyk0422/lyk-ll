package com.example.starter.fermentation;

import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiveRequest;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterSourceLiquorRequest;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterTankRequest;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.example.starter.fermentation.service.ReceivingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合批接收 H2 真实并发测试：两组请求争同一接收余量或同一受控源，
 * 由数据库唯一约束与乐观版本条件更新按事务提交次序裁决，恰好一方受理、另一方整单回滚。
 */
class MergeConcurrencyTest extends BaseFermentationTest {

    private static final LocalDateTime RECEIVED_AT =
            BaseFermentationTest.T0.plusDays(30);

    @Autowired
    private ReceivingService receivingService;

    @BeforeEach
    void ensureFourthTank() {
        jdbc.update("INSERT INTO fermentation_tank (tank_code, capacity_liters)"
                + " SELECT 'TANK-4', 5000 WHERE NOT EXISTS ("
                + "SELECT 1 FROM fermentation_tank WHERE tank_code = 'TANK-4')");
    }

    /** 并发驱动结果：成功受理数 / 冲突拒绝数（409）/ 其他结果数。 */
    private record RaceCounts(int accepted, int conflict, int other) {
    }

    private RaceCounts runRace(Callable<?>... tasks) throws Exception {
        int threads = tasks.length;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (Callable<?> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
                try {
                    Object r = task.call();
                    if (r instanceof StoredResponse sr && sr.status() == 201) {
                        accepted.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (ApiException e) {
                    if (e.status().value() == 409) {
                        conflict.incrementAndGet();
                    } else if (e.status().value() == 422) {
                        // 超容量在“读到已提交余量”路径下也可能以 422 表达，计入冲突裁决
                        conflict.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池终止超时");
        return new RaceCounts(accepted.get(), conflict.get(), other.get());
    }

    @Test
    @DisplayName("两组争同一接收余量（空罐恰满）：按事务次序仅一方受理，另一方回滚，累计恰 100 升")
    void contendForSameRemainingCapacityExactlyOneWins() throws Exception {
        // 四个不同源批，每组合计恰 100 升；空罐容量恰 100 升
        long a1 = driveToJudged("B-RACE-C1", "TANK-1");
        long a2 = driveToJudged("B-RACE-C2", "TANK-2");
        long b1 = driveToJudged("B-RACE-C3", "TANK-3");
        long b2 = driveToJudged("B-RACE-C4", "TANK-4");
        receivingService.registerSourceLiquor(a1, new RegisterSourceLiquorRequest(
                newRequestId(), new BigDecimal("50")));
        receivingService.registerSourceLiquor(a2, new RegisterSourceLiquorRequest(
                newRequestId(), new BigDecimal("50")));
        receivingService.registerSourceLiquor(b1, new RegisterSourceLiquorRequest(
                newRequestId(), new BigDecimal("50")));
        receivingService.registerSourceLiquor(b2, new RegisterSourceLiquorRequest(
                newRequestId(), new BigDecimal("50")));
        receivingService.registerTank(new RegisterTankRequest(
                newRequestId(), "RT-RACE-CAP", new BigDecimal("100")));

        RaceCounts counts = runRace(
                () -> receivingService.mergeReceive(new MergeReceiveRequest(
                        newRequestId(), "RT-RACE-CAP", List.of(a1, a2), 1, RECEIVED_AT)),
                () -> receivingService.mergeReceive(new MergeReceiveRequest(
                        newRequestId(), "RT-RACE-CAP", List.of(b1, b2), 1, RECEIVED_AT)));

        assertEquals(1, counts.accepted(), "争同一余量应恰好一方受理");
        assertEquals(1, counts.conflict(), "另一方应冲突回滚");
        assertEquals(0, counts.other());

        BigDecimal received = jdbc.queryForObject(
                "SELECT received_liters FROM receiving_tank WHERE tank_code='RT-RACE-CAP'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("100.000").compareTo(received), "累计接收应恰为 100 升");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM merge_receipt", Integer.class),
                "败方不得留下受理单");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM liquor_writeoff", Integer.class),
                "仅胜方两源被核销");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE status='TRANSFERRED'", Integer.class),
                "仅胜方两源进入转出终态");
        List<Long> judgedRemaining = jdbc.queryForList(
                "SELECT id FROM fermentation_batch WHERE status='JUDGED' ORDER BY id", Long.class);
        assertEquals(2, judgedRemaining.size(), "败方两源应保持待转出");
    }

    @Test
    @DisplayName("两组争同一受控源：唯一核销裁决仅一方受理，败方整单回滚且不核销其另一源")
    void contendForSameSourceExactlyOneWins() throws Exception {
        // A=[s0,s1]，B=[s0,s2]，共享 s0；接收罐容量充足，冲突点为 s0 的唯一核销
        long s0 = driveToJudged("B-RACE-S0", "TANK-1");
        long s1 = driveToJudged("B-RACE-S1", "TANK-2");
        long s2 = driveToJudged("B-RACE-S2", "TANK-3");
        for (long id : new long[]{s0, s1, s2}) {
            receivingService.registerSourceLiquor(id, new RegisterSourceLiquorRequest(
                    newRequestId(), new BigDecimal("10")));
        }
        receivingService.registerTank(new RegisterTankRequest(
                newRequestId(), "RT-RACE-SRC", new BigDecimal("1000")));

        RaceCounts counts = runRace(
                () -> receivingService.mergeReceive(new MergeReceiveRequest(
                        newRequestId(), "RT-RACE-SRC", List.of(s0, s1), 1, RECEIVED_AT)),
                () -> receivingService.mergeReceive(new MergeReceiveRequest(
                        newRequestId(), "RT-RACE-SRC", List.of(s0, s2), 1, RECEIVED_AT)));

        assertEquals(1, counts.accepted(), "争同源应恰好一方受理");
        assertEquals(1, counts.conflict(), "另一方应因核销冲突整单回滚");
        assertEquals(0, counts.other());

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM merge_receipt", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM liquor_writeoff", Integer.class),
                "共享源与胜方另一源各一条核销，败方另一源不核销");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM liquor_writeoff WHERE batch_id = ?", Integer.class, s0),
                "共享源仅一条核销");
        Integer s2Writeoff = jdbc.queryForObject(
                "SELECT COUNT(*) FROM liquor_writeoff WHERE batch_id = ?", Integer.class, s2);
        Integer s1Writeoff = jdbc.queryForObject(
                "SELECT COUNT(*) FROM liquor_writeoff WHERE batch_id = ?", Integer.class, s1);
        assertTrue((s1Writeoff == 1 && s2Writeoff == 0) || (s1Writeoff == 0 && s2Writeoff == 1),
                "胜方搭配源被核销，败方搭配源必须保持未核销");
        BigDecimal received = jdbc.queryForObject(
                "SELECT received_liters FROM receiving_tank WHERE tank_code='RT-RACE-SRC'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("20.000").compareTo(received), "仅胜方 20 升入罐");
    }
}
