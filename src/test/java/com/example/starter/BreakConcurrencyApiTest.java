package com.example.starter;

import com.example.starter.breakmodel.BreakService;
import com.example.starter.breakmodel.BreakStartRequest;
import com.example.starter.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插播并发：同优先级并发请求按 requestId 字典序确定唯一胜者；同键并发请求幂等。
 */
class BreakConcurrencyApiTest extends AbstractApiTest {

    @Autowired
    private BreakService breakService;

    @Test
    void 同优先级并发插播由最小requestId唯一胜出() throws Exception {
        publishStandardPlaylist("ch-race");
        postJson("/api/ticks", """
                {"channelId": "ch-race", "atMs": 100}
                """);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "req-" + i;
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    breakService.start(new BreakStartRequest(
                            "ch-race", requestId, 5, 300L, "live-" + requestId));
                    return "OK:" + requestId;
                } catch (ApiException e) {
                    return "REJECTED:" + requestId + ":" + e.status().value();
                }
            }));
        }
        gate.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 每个请求都有确定结果：成功或被 409 拒绝
        assertEquals(threads, outcomes.size());
        for (String outcome : outcomes) {
            assertTrue(outcome.startsWith("OK:") || outcome.startsWith("REJECTED:"),
                    "意外结果: " + outcome);
        }

        // 唯一胜者：字典序最小的 req-0 处于 ACTIVE，其余均已 ENDED 或被拒
        String active = jdbcTemplate.queryForObject(
                "SELECT request_id FROM break_request WHERE channel_id = 'ch-race' AND status = 'ACTIVE'",
                String.class);
        assertEquals("req-0", active);
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM break_request WHERE channel_id = 'ch-race' AND status = 'ACTIVE'",
                Integer.class);
        assertEquals(1, activeCount);

        // 在播 as-run 记录唯一且属于胜者
        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM as_run WHERE channel_id = 'ch-race' AND status = 'OPEN'",
                Integer.class);
        assertEquals(1, openCount);
        String openBreak = jdbcTemplate.queryForObject(
                "SELECT break_request_id FROM as_run WHERE channel_id = 'ch-race' AND status = 'OPEN'",
                String.class);
        assertEquals("req-0", openBreak);
    }

    @Test
    void 同键同参并发请求返回同一冻结结果() throws Exception {
        publishStandardPlaylist("ch-race-idem");
        postJson("/api/ticks", """
                {"channelId": "ch-race-idem", "atMs": 100}
                """);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return breakService.start(new BreakStartRequest(
                        "ch-race-idem", "req-same", 5, 300L, "live-same")).asRunSeq();
            }));
        }
        gate.countDown();
        List<Long> seqs = new ArrayList<>();
        for (Future<Long> future : futures) {
            seqs.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 全部请求返回首次冻结的同一 asRunSeq，且只生成一条插播记录
        assertEquals(threads, seqs.size());
        assertEquals(1, seqs.stream().distinct().count());
        Integer breakRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM break_request WHERE channel_id = 'ch-race-idem'", Integer.class);
        assertEquals(1, breakRows);
        Integer breakAsRun = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM as_run WHERE channel_id = 'ch-race-idem' AND kind = 'BREAK'",
                Integer.class);
        assertEquals(1, breakAsRun);
    }
}
