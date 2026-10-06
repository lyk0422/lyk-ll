package com.example.starter.dimming;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 规则5：写操作按 requestId 幂等——同键同参返回首次结果，同键异参409；
 * 并发下同键请求至多执行一次；同一回路并发指令被串行化，不出现重复留痕。
 */
class IdempotencyConcurrencyTest extends BaseIntegrationTest {

    @Test
    void sameKeySameParamsReturnsFirstResult() throws Exception {
        var first = dim("TUN-A-01", "idem-1", 4, "MANUAL");
        assertEquals(200, first.getResponse().getStatus());
        var replay = dim("TUN-A-01", "idem-1", 4, "MANUAL");
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(body(first).toString(), body(replay).toString());
        assertEquals(1, recordCount("TUN-A-01"));
        assertEquals(4, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        var first = dim("TUN-A-01", "idem-2", 4, "MANUAL");
        assertEquals(200, first.getResponse().getStatus());
        var conflict = dim("TUN-A-01", "idem-2", 5, "MANUAL");
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", body(conflict).get("code").asText());
        assertEquals(1, recordCount("TUN-A-01"));
        assertEquals(4, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void failedRequestDoesNotBlockRetry() throws Exception {
        var failed = dim("TUN-A-01", "idem-3", 4, "BADSOURCE");
        assertEquals(400, failed.getResponse().getStatus());
        var retried = dim("TUN-A-01", "idem-3", 4, "MANUAL");
        // 指纹不同（source不同）且首笔失败已清除占位，允许以新参数执行
        assertEquals(200, retried.getResponse().getStatus());
        assertEquals(1, recordCount("TUN-A-01"));
    }

    @Test
    void accidentLockIsIdempotent() throws Exception {
        var first = lockAccident("TUN-A-01", "idem-4", "ALARM-100");
        assertEquals(200, first.getResponse().getStatus());
        var replay = lockAccident("TUN-A-01", "idem-4", "ALARM-100");
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(body(first).toString(), body(replay).toString());
        Integer locks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accident_lock_log WHERE circuit_code = 'TUN-A-01' AND action = 'LOCK'",
                Integer.class);
        assertEquals(1, locks);
    }

    @Test
    void concurrentSameRequestExecutesOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/circuits/TUN-A-01/commands")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"idem-5\",\"targetLevel\":4,\"source\":\"MANUAL\"}"))
                        .andReturn();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int ok = 0;
        int inFlight = 0;
        String firstBody = null;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            int status = result.getResponse().getStatus();
            if (status == 200) {
                ok++;
                String responseBody = result.getResponse().getContentAsString();
                if (firstBody == null) {
                    firstBody = responseBody;
                } else {
                    assertEquals(firstBody, responseBody, "同键同参必须返回首次结果");
                }
            } else if (status == 409) {
                inFlight++;
                assertTrue(result.getResponse().getContentAsString().contains("REQUEST_IN_FLIGHT"));
            }
        }
        assertTrue(ok >= 1, "至少一个请求成功");
        assertEquals(threads, ok + inFlight);
        assertEquals(1, recordCount("TUN-A-01"), "并发同键请求至多留痕一次");
        assertEquals(4, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void concurrentDifferentRequestsOnSameCircuitAreSerialized() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        String[] bodies = {
                "{\"requestId\":\"conc-1\",\"targetLevel\":4,\"source\":\"MANUAL\"}",
                "{\"requestId\":\"conc-2\",\"targetLevel\":5,\"source\":\"MANUAL\"}"};
        for (String requestBody : bodies) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/circuits/TUN-A-01/commands")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int ok = 0;
        int stabilityRejected = 0;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            if (result.getResponse().getStatus() == 200) {
                ok++;
            } else {
                assertEquals(409, result.getResponse().getStatus());
                assertTrue(result.getResponse().getContentAsString().contains("STABILITY_NOT_ELAPSED"));
                stabilityRejected++;
            }
        }
        // 串行化后先执行者成功，后者因稳定时限被拒；留痕恰好一条且与最终档位一致
        assertEquals(1, ok);
        assertEquals(1, stabilityRejected);
        assertEquals(1, recordCount("TUN-A-01"));
        int finalLevel = state("TUN-A-01").get("currentLevel").asInt();
        Integer recordedTo = jdbc.queryForObject(
                "SELECT to_level FROM dimming_record WHERE circuit_code = 'TUN-A-01'", Integer.class);
        assertEquals(recordedTo.intValue(), finalLevel);
        Integer executing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dimming_command WHERE status = 'EXECUTING'", Integer.class);
        assertEquals(0, executing, "不应残留执行中指令");
    }
}
