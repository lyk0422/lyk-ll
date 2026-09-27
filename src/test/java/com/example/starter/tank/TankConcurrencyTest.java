package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionIssueRequest;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.ReplenishRequest;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 储罐计量并发边界（H2 真实并发事务）：
 * <ul>
 *   <li>同一计划“回执 vs 取消”并发，唯一计量，恰一方成功，不重复返还、不超扣；</li>
 *   <li>补液并发受容量约束，绝不超罐；</li>
 *   <li>同键同参回执并发只扣一次。</li>
 * </ul>
 */
class TankConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 26, 14, 0);

    @Autowired
    private TankService tankService;

    private void register(String station, String requestId, String capacity,
                          String safety, String initial) {
        tankService.register(new TankRegisterRequest(station, requestId, "TK-" + requestId,
                new BigDecimal(capacity), new BigDecimal(safety), new BigDecimal(initial),
                T.minusHours(1)));
    }

    @Test
    void concurrentReceiptAndCancelSettleExactlyOnce() throws Exception {
        register("ST01", "CC-REG", "100.000", "20.000", "50.000");
        tankService.issue(new InjectionIssueRequest("ST01", "CC-PLAN",
                new BigDecimal("30.000"), 1, T));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger receipts = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                start.await();
                try {
                    if (idx % 2 == 0) {
                        // 回执时储罐版本可能已变，失败即记为被唯一裁决拒绝
                        tankService.receipt(new InjectionReceiptRequest("ST01", "CC-RC" + idx,
                                "CC-PLAN", new BigDecimal("30.000"), 2, T.plusMinutes(idx)));
                        receipts.incrementAndGet();
                    } else {
                        tankService.cancel(new InjectionCancelRequest("ST01", "CC-CN" + idx,
                                "CC-PLAN", 2, T.plusMinutes(idx)));
                        cancels.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, receipts.get() + cancels.get(), "回执与取消中恰一方成功，唯一计量");
        assertEquals(threads - 1, rejected.get(), "其余并发结算必须被拒绝");
        assertEquals(1, count("SELECT COUNT(*) FROM injection_plan WHERE request_id = 'CC-PLAN' "
                + "AND status IN ('EXECUTED','CANCELLED')"));

        TankStatusResponse status = tankService.status("ST01");
        // 无论回执还是取消：预占必须归零；回执扣30余额20，取消不扣余额50；二者只发生其一
        assertEquals(0, new BigDecimal("0.000").compareTo(status.reservedKg()), "不得残留预占/重复返还");
        boolean consumed = receipts.get() == 1;
        String expectedBalance = consumed ? "20.000" : "50.000";
        assertEquals(0, new BigDecimal(expectedBalance).compareTo(status.balanceKg()));
    }

    @Test
    void concurrentReplenishmentsNeverExceedCapacity() throws Exception {
        // 容量60 余额50，两笔各8kg：最多一笔入账（50+8=58），第二笔将达66超容量被拒
        register("ST01", "CR-REG", "60.000", "0.000", "50.000");
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                start.await();
                try {
                    tankService.replenish(new ReplenishRequest("ST01", "CR-RP" + idx,
                            "BATCH-CR" + idx, new BigDecimal("8.000"), new BigDecimal("20.000"),
                            "H0" + idx, "H1" + idx, 1, T.plusMinutes(idx)));
                    success.incrementAndGet();
                } catch (RuntimeException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, success.get(), "并发补液只允许一笔不超容量的入账");
        assertEquals(1, rejected.get(), "超容量那笔必须被拒绝");
        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, new BigDecimal("58.000").compareTo(status.balanceKg()), "余额绝不超罐");
        assertEquals(1, count("SELECT COUNT(*) FROM replenishment_order"), "仅一张补液单原子入账");
    }

    @Test
    void concurrentSameKeyReceiptsDeductOnlyOnce() throws Exception {
        register("ST01", "CS-REG", "100.000", "20.000", "50.000");
        tankService.issue(new InjectionIssueRequest("ST01", "CS-PLAN",
                new BigDecimal("30.000"), 1, T));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        // 同键同参：expectedVersion 固定 2，成功首扣后版本变3，但幂等重放不校验业务版本
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                start.await();
                return tankService.receipt(new InjectionReceiptRequest("ST01", "CS-RC",
                        "CS-PLAN", new BigDecimal("30.000"), 2, T.plusMinutes(1)));
            });
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, new BigDecimal("20.000").compareTo(status.balanceKg()), "同键并发回执只扣一次30kg");
        assertEquals(0, new BigDecimal("0.000").compareTo(status.reservedKg()));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'CONSUME'"));
        assertEquals(1, count("SELECT COUNT(*) FROM injection_plan WHERE request_id = 'CS-PLAN' "
                + "AND status = 'EXECUTED'"));
    }
}
