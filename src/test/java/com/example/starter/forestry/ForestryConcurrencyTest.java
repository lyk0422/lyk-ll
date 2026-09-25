package com.example.starter.forestry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.forestry.service.ForestryService.ApplicationResult;
import com.example.starter.forestry.service.ForestryService.ConfirmResult;
import com.example.starter.forestry.service.ForestryService.LicenseResult;
import com.example.starter.forestry.support.ForestryException;
import com.example.starter.forestry.support.LicenseExpiredException;

/**
 * 并发与幂等边界测试：真实 H2 行锁与事务，协调真实并发操作并设置超时。
 */
class ForestryConcurrencyTest extends ForestryTestBase {

    @Test
    @DisplayName("并发发证：同一限额档位串行校验，合计不得超限")
    void concurrentIssuanceNeverExceedsQuota() throws Exception {
        givenQuota(2026, "PINE", "950");
        int threads = 10;
        List<Long> applicationIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            givenCommercialPlot("P" + i, 30, 25, "PINE");
            ApplicationResult application = service.submitApplication("app-" + i, "P" + i,
                    "OP-" + i, 2026, new BigDecimal("100"));
            assertEquals("APPROVED", application.status());
            applicationIds.add(application.applicationId());
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger quotaExceeded = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        runConcurrently(threads, i -> () -> {
            try {
                service.issueLicense("lic-" + i, applicationIds.get(i));
                success.incrementAndGet();
            } catch (ForestryException ex) {
                if ("QUOTA_EXCEEDED".equals(ex.code())) {
                    quotaExceeded.incrementAndGet();
                } else {
                    unexpected.incrementAndGet();
                }
            }
            return null;
        });

        // 限额 950，每笔 100：恰 9 笔成功，合计占用 900，不超 950
        assertEquals(0, unexpected.get(), "不应出现非限额类异常");
        assertEquals(9, success.get());
        assertEquals(1, quotaExceeded.get());
        assertEquals(0, new BigDecimal("900").compareTo(service.getQuota(2026, "PINE").usedVolume()));
    }

    @Test
    @DisplayName("并发确认同一证件：唯一终态 CONFIRMED，仅生成一张更新任务")
    void concurrentConfirmSingleTerminalState() throws Exception {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");
        ApplicationResult application = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("100"));
        LicenseResult license = service.issueLicense("lic-1", application.applicationId());

        int threads = 4;
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        runConcurrently(threads, i -> () -> {
            try {
                ConfirmResult result = service.confirmHarvest(license.licenseId());
                if ("CONFIRMED".equals(result.status())) {
                    confirmed.incrementAndGet();
                }
            } catch (ForestryException ex) {
                unexpected.incrementAndGet();
            }
            return null;
        });

        assertEquals(0, unexpected.get(), "重复确认应返回当前状态而非异常");
        assertEquals(threads, confirmed.get());
        assertEquals("CONFIRMED", service.getLicense(license.licenseId()).status());
        // 仅生成一张迹地更新任务（license_id 唯一约束）
        Integer taskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM regeneration_task WHERE license_id = ?",
                Integer.class, license.licenseId());
        assertEquals(1, taskCount);
    }

    @Test
    @DisplayName("并发确认逾期证件：唯一终态 VOID，限额只释放一次")
    void concurrentVoidAndConfirmSingleTerminalState() throws Exception {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");
        ApplicationResult application = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("100"));
        LicenseResult license = service.issueLicense("lic-1", application.applicationId());

        // 推进到第 91 日，证件已逾期
        clock.setDate(LocalDate.of(2026, 5, 30));

        int threads = 4;
        AtomicInteger expired = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        runConcurrently(threads, i -> () -> {
            try {
                service.confirmHarvest(license.licenseId());
            } catch (LicenseExpiredException ex) {
                expired.incrementAndGet();
            } catch (ForestryException ex) {
                if (!"LICENSE_VOID".equals(ex.code())) {
                    unexpected.incrementAndGet();
                }
            }
            return null;
        });

        assertEquals(0, unexpected.get(), "只允许 LICENSE_EXPIRED 或 LICENSE_VOID");
        assertTrue(expired.get() >= 1, "至少一个线程观察到逾期作废");
        assertEquals("VOID", service.getLicense(license.licenseId()).status());
        // 限额只释放一次：占用 100，作废后归零（不会重复释放成负数）
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getQuota(2026, "PINE").usedVolume()));
        // 作废日期唯一
        assertEquals(LocalDate.of(2026, 5, 30), service.getLicense(license.licenseId()).voidDate());
    }

    /** 以 CountDownLatch 协调真实并发，等待全部完成并设置超时。 */
    private void runConcurrently(int threads, TaskFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Void> task = factory.task(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                task.call();
                return null;
            }));
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "线程未就绪");
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务超时");
        for (Future<?> future : futures) {
            future.get();
        }
    }

    @FunctionalInterface
    private interface TaskFactory {
        Callable<Void> task(int index);
    }
}
