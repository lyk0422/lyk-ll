package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.cems.repo.OrderRepository;
import com.example.starter.cems.repo.OutfallRepository;
import com.example.starter.cems.service.CemsService;
import com.example.starter.cems.testsupport.TestClockConfiguration;
import com.example.starter.cems.web.dto.MutationResponse;
import com.example.starter.cems.web.dto.OrderView;
import com.example.starter.cems.web.dto.OutfallView;
import com.example.starter.cems.web.dto.ReadingRequest;
import com.example.starter.cems.web.dto.RegisterRequest;

/**
 * H2（MODE=MySQL）真实事务/并发边界测试：
 * 限排令插入失败时基数冻结随事务回滚；并发下达仅一个受理；并发同 requestId 唯一受理。
 */
class CemsTransactionTest extends AbstractCemsTest {

    @Autowired
    private CemsService service;
    @Autowired
    private OutfallRepository outfallRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private OrderView sampleOrder(String outfallCode, String requestId,
                                  LocalDateTime ts, BigDecimal baseline) {
        return new OrderView(null, outfallCode, requestId,
                "2026-09-20T09:00:00,2026-09-20T10:00:00,2026-09-20T11:00:00",
                "ISSUED", ts, ts.plusHours(2), false, null, false, null, false,
                baseline, ts, null, null, null, null);
    }

    @Test
    void 限排令插入失败_基数冻结随同一事务回滚_不存在限排生效而基数未定() {
        LocalDateTime ts = LocalDateTime.parse("2026-09-20T11:05:00");

        // 先独立提交一个占用 requestId=DUP-REQ 的限排令（属于另一排口）
        transactionTemplate.executeWithoutResult(s -> {
            outfallRepository.insert(new OutfallView("OUT-OTHER", "另一排口", "REGISTERED",
                    new BigDecimal("10"), new BigDecimal("5"), null, null, ts, ts, false));
            orderRepository.insert(sampleOrder("OUT-OTHER", "DUP-REQ", ts, new BigDecimal("0")));
        });

        // 目标排口
        transactionTemplate.executeWithoutResult(s -> {
            outfallRepository.insert(new OutfallView("OUT-ATOM", "原子性排口", "REGISTERED",
                    new BigDecimal("10"), new BigDecimal("5"), null, null, ts, ts, false));
        });

        // 同一事务内：先冻结基数，再因 requestId 唯一约束导致限排令插入失败 → 整体回滚
        RuntimeException caught = null;
        try {
            transactionTemplate.executeWithoutResult(s -> {
                outfallRepository.freezeBaseline("OUT-ATOM", new BigDecimal("100"), ts, ts);
                orderRepository.insert(sampleOrder("OUT-ATOM", "DUP-REQ", ts, new BigDecimal("100")));
            });
        } catch (RuntimeException ex) {
            caught = ex;
        }
        assertNotNull(caught, "限排令插入必须失败");
        assertTrue(nestedIs(caught, DuplicateKeyException.class),
                "失败原因应为唯一约束冲突，实际: " + caught);

        // 回滚后：排口未进入限排、无冻结基数、无限排令
        OutfallView outfall = outfallRepository.findByCode("OUT-ATOM");
        assertEquals("REGISTERED", outfall.status());
        assertNull(outfall.totalBaseline());
        assertNull(outfall.baselineFrozenAt());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM restriction_order WHERE outfall_code = 'OUT-ATOM'",
                Integer.class));
        // 另一排口已提交数据不受影响
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM restriction_order WHERE outfall_code = 'OUT-OTHER'",
                Integer.class));
    }

    @Test
    void 成功下达时限排生效与基数冻结同时可见() {
        prepareReadableOutfall("OUT-OKATOM");
        LocalDateTime ts = LocalDateTime.parse("2026-09-20T11:05:00");
        OrderView order = service.issueRestrictionOrder("OUT-OKATOM", "MANUAL-OK",
                java.util.List.of(
                        LocalDateTime.parse("2026-09-20T09:00:00"),
                        LocalDateTime.parse("2026-09-20T10:00:00"),
                        LocalDateTime.parse("2026-09-20T11:00:00")), ts);
        assertNotNull(order.id());
        OutfallView outfall = outfallRepository.findByCode("OUT-OKATOM");
        assertEquals("RESTRICTED", outfall.status());
        assertEquals(0, new BigDecimal("21").compareTo(outfall.totalBaseline()));
        assertNotNull(outfall.baselineFrozenAt());
    }

    @Test
    void 并发下达同一排口限排令_唯一受理_其余回滚() throws Exception {
        prepareReadableOutfall("OUT-CONC");
        LocalDateTime ts = LocalDateTime.parse("2026-09-20T11:05:00");
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        service.issueRestrictionOrder("OUT-CONC", "MANUAL-" + idx,
                                java.util.List.of(
                                        LocalDateTime.parse("2026-09-20T09:00:00"),
                                        LocalDateTime.parse("2026-09-20T10:00:00"),
                                        LocalDateTime.parse("2026-09-20T11:00:00")), ts);
                        success.incrementAndGet();
                    } catch (DuplicateKeyException dup) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, success.get(), "仅一个下达成功");
        assertEquals(threads - 1, rejected.get(), "其余被唯一约束拒绝");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM restriction_order WHERE outfall_code = 'OUT-CONC'",
                Integer.class), "仅一个限排令");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM restriction_order WHERE active_slot = 'OUT-CONC'",
                Integer.class), "仅一个执行中占位列");
        OutfallView outfall = outfallRepository.findByCode("OUT-CONC");
        assertEquals("RESTRICTED", outfall.status());
        assertEquals(0, new BigDecimal("21").compareTo(outfall.totalBaseline()),
                "冻结基数为小时均值累计 6+7+8=21");
    }

    @Test
    void 并发相同requestId登记_仅受理一次_后者回放首次结果() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<MutationResponse<OutfallView>>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return service.register(new RegisterRequest("IDEM-CONC-1", "OUT-IDEM-CONC",
                            "并发同键排口", new BigDecimal("10"), new BigDecimal("5")));
                }));
            }
            start.countDown();
            int firstCount = 0;
            int replayCount = 0;
            for (Future<MutationResponse<OutfallView>> f : futures) {
                MutationResponse<OutfallView> r = f.get(30, TimeUnit.SECONDS);
                if (r.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
            }
            assertEquals(1, firstCount, "仅一个首次受理");
            assertEquals(1, replayCount, "另一个回放首次结果");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outfall WHERE outfall_code = 'OUT-IDEM-CONC'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_event WHERE request_id = 'IDEM-CONC-1'",
                Integer.class));
    }

    /** 准备已登记、含 09/10/11 三个小时读数（6/7/8，累计 21）的排口。 */
    private void prepareReadableOutfall(String code) {
        service.register(new RegisterRequest("reg-" + code, code, "排口-" + code,
                new BigDecimal("10"), new BigDecimal("5")));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T11:05:00");
        transactionTemplate.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO monitor_reading(outfall_code, period_type, period_start, "
                            + "avg_value, is_late, request_id, created_at) VALUES "
                            + "(?,'HOUR','2026-09-20 09:00:00',6,0,?, '2026-09-20 11:05:00'),"
                            + "(?,'HOUR','2026-09-20 10:00:00',7,0,?, '2026-09-20 11:05:00'),"
                            + "(?,'HOUR','2026-09-20 11:00:00',8,0,?, '2026-09-20 11:05:00')",
                    code, code + "-r1", code, code + "-r2", code, code + "-r3");
        });
    }

    private boolean nestedIs(Throwable throwable, Class<?> type) {
        Throwable t = throwable;
        while (t != null) {
            if (type.isInstance(t)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
