package com.example.starter.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.food.service.BusinessException;
import com.example.starter.food.service.IdempotencyService;
import com.example.starter.food.web.Dtos;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 真实并发事务测试（H2 唯一约束 uk_filing_active）：同一产品同时刻并发备案仅一个成功。
 */
class ConcurrencyFilingTest extends AbstractFoodTest {

    /** 两个已批准版本并发备案：恰好一个 200 成功，其余 409；最终仅一个有效备案。 */
    @Test
    void concurrentFilingsOnlyOneSucceeds() throws Exception {
        String product = "PRD-CONC";
        Dtos.RecipeResponse r1 = submit("conc-submit-1", product, "v1", "BEV-01",
                List.of(add("P001", "0.100000")), null);
        Dtos.RecipeResponse r2 = submit("conc-submit-2", product, "v2", "BEV-01",
                List.of(add("P001", "0.200000")), null);
        // r1 正常批准：此时无有效备案（旧备案不存在），不触发暂停
        review("conc-rev-1", r1.id(), "V1");
        review("conc-rev-2", r2.id(), "V1");
        approve("conc-app-1", r1.id());
        approve("conc-app-2", r2.id());

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> attemptFiling("conc-file-1", r1.id(), barrier, success, conflict)),
                    pool.submit(() -> attemptFiling("conc-file-2", r2.id(), barrier, success, conflict)));
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, success.get(), "并发备案必须恰好一个成功");
        assertEquals(1, conflict.get(), "并发失败方必须收到 409");
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(1) FROM filing WHERE product_code = ? AND active_token = 'X'",
                Integer.class, product);
        assertEquals(1, active.intValue(), "同一产品同时刻只允许一个有效备案版本");
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(1) FROM filing WHERE product_code = ?", Integer.class, product);
        assertEquals(1, total.intValue(), "失败方事务回滚，不留部分备案记录");
    }

    private void attemptFiling(String requestId, long recipeId, CyclicBarrier barrier,
                               AtomicInteger success, AtomicInteger conflict) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            IdempotencyService.Result r = service.fileFiling(new Dtos.FileFilingRequest(requestId, recipeId));
            if (r.statusCode() == 200) {
                success.incrementAndGet();
            } else if (r.statusCode() == 409) {
                conflict.incrementAndGet();
            }
        } catch (BusinessException e) {
            if (e.getStatus() == 409) {
                conflict.incrementAndGet();
            } else {
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
