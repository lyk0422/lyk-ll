package com.example.starter.dye;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发排缸：同一染缸同时刻仅一个缸次可受理。两个真实线程在屏障处对齐后并发排同一染缸，
 * 断言恰一方 200、另一方 409，且数据库中该缸仅一条缸次、active_run_id 唯一占用。
 */
class ConcurrentScheduleIntegrationTest extends FlowIntegrationTest {

    @Test
    void onlyOneRunAcceptedForSameVatConcurrently() throws Exception {
        dyestuff("D1", "100000");
        vat("V1", "500");
        publishRecipe("R1", 10, Map.of("D1", "2.0000"));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < threads; i++) {
                final String runNo = "RUN-C" + i;
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        MockHttpServletResponse resp = postJson("/api/runs", Map.of(
                                "runNo", runNo, "vatCode", "V1", "recipeCode", "R1",
                                "fabricKg", new java.math.BigDecimal("100")))
                                .andReturn().getResponse();
                        statuses.add(resp.getStatus());
                    } catch (Exception e) {
                        errors.add(e.toString());
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).isEmpty();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        // 仅一条缸次落库（失败方随事务回滚）
        Integer runCount = jdbc.queryForObject("select count(*) from vat_run", Integer.class);
        assertThat(runCount).isEqualTo(1);
        // 染缸唯一占用
        String active = jdbc.queryForObject(
                "select r.run_no from dye_vat v join vat_run r on r.id = v.active_run_id where v.vat_code='V1'",
                String.class);
        assertThat(active).startsWith("RUN-C");
    }
}
