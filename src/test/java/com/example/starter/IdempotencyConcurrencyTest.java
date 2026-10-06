package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 幂等与并发：同键同参返回首次结果、同键异参 409、
 * 并发同键仅生效一次、并发同库区仅一条进行中处置链。
 */
class IdempotencyConcurrencyTest extends BaseIntegrationTest {

    @Test
    void sameKeySameParamsReturnsFirstResult() throws Exception {
        Map<String, Object> body = Map.of("requestId", "rpt-idem", "reporter", "张三",
                "description", "发现虫迹");
        long first = reportCase("A", "rpt-idem");

        // 同键同参重放：返回首次结果，不产生新记录
        postJson("/api/zones/A/pest-reports", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(first));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pest_case", Long.class));
    }

    @Test
    void sameKeyDifferentParamsRejected() throws Exception {
        reportCase("A", "rpt-idem");

        // 同键异参：409
        postJson("/api/zones/A/pest-reports",
                Map.of("requestId", "rpt-idem", "reporter", "李四", "description", "另一处虫迹"))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pest_case", Long.class));
    }

    @Test
    void concurrentSameKeySameParamsTakeEffectOnce() throws Exception {
        int threads = 8;
        Map<String, Object> body = Map.of("requestId", "rpt-conc", "reporter", "张三",
                "description", "发现虫迹");
        List<Future<MvcResult>> futures = submitConcurrently(threads, i -> body);

        Set<Long> caseIds = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(200, result.getResponse().getStatus());
            caseIds.add(objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("caseId").asLong());
        }
        assertEquals(1, caseIds.size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pest_case", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Long.class));
    }

    @Test
    void concurrentReportsSameZoneAllowSingleActiveChain() throws Exception {
        int threads = 8;
        List<Future<MvcResult>> futures = submitConcurrently(threads,
                i -> Map.of("requestId", "rpt-zone-" + i, "reporter", "张三",
                        "description", "发现虫迹"));

        int ok = 0;
        int conflict = 0;
        for (Future<MvcResult> future : futures) {
            int responseStatus = future.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            if (responseStatus == 200) {
                ok++;
            } else if (responseStatus == 409) {
                conflict++;
            }
        }
        assertEquals(1, ok);
        assertEquals(threads - 1, conflict);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pest_case", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM zone_active_case", Long.class));
    }

    /**
     * 协调真实并发：全部线程就绪后同时放行，设置超时避免悬挂。
     */
    private List<Future<MvcResult>> submitConcurrently(int threads,
            java.util.function.IntFunction<Map<String, Object>> bodyFactory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/zones/A/pest-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bodyFactory.apply(index))))
                        .andReturn();
            }));
        }
        assertEquals(true, ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertEquals(true, pool.awaitTermination(60, TimeUnit.SECONDS));
        return futures;
    }
}
