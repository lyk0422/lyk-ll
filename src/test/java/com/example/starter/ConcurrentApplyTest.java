package com.example.starter;

import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.service.DiveService;
import com.example.starter.service.IdempotencyService;
import com.example.starter.web.dto.ApplyResult;
import org.junit.jupiter.api.Test;

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
 * 真实并发测试（H2 MVStore）：同一潜水组同时刻并发申请唯一受理。
 */
class ConcurrentApplyTest extends AbstractIntegrationTest {

    /** 同组、不同 requestId 并发申请：恰一个受理成功，其余 409，数据库最终只有一条潜次。 */
    @Test
    void sameTeamConcurrentApplies_onlyOneAccepted() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        IdempotencyService.Outcome<ApplyResult> out = diveService.apply(
                                new ApplyRequest("CONC-TEAM-" + idx, "D001", "D002", 18, 50));
                        return out.statusCode();
                    } catch (com.example.starter.web.ApiException e) {
                        return e.getStatus();
                    }
                }));
            }
            start.countDown();
            int created = 0;
            int conflict = 0;
            for (Future<Integer> f : futures) {
                int code = f.get(30, TimeUnit.SECONDS);
                if (code == 201) {
                    created++;
                } else if (code == 409) {
                    conflict++;
                }
            }
            assertEquals(1, created, "同组并发申请必须恰一个被受理");
            assertEquals(threads - 1, conflict, "其余并发申请必须为 409");
            assertEquals(1,
                    jdbc.queryForObject("SELECT COUNT(1) FROM dive WHERE team_key='D001|D002'",
                            Integer.class).intValue());
            assertEquals(1,
                    jdbc.queryForObject("SELECT COUNT(1) FROM dive WHERE active_team_slot='D001|D002'",
                            Integer.class).intValue());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 同 requestId、同参数并发：唯一受理，败方重放首次结果（同为 201），不产生重复潜次。 */
    @Test
    void sameRequestIdConcurrentApplies_singleAcceptOthersReplay() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    IdempotencyService.Outcome<ApplyResult> out = diveService.apply(
                            new ApplyRequest("CONC-IDEM", "D003", "D004", 24, 30));
                    return out.statusCode() + ":" + out.replayed();
                }));
            }
            start.countDown();
            int first = 0;
            int replay = 0;
            for (Future<String> f : futures) {
                String r = f.get(30, TimeUnit.SECONDS);
                if ("201:false".equals(r)) {
                    first++;
                } else if ("201:true".equals(r)) {
                    replay++;
                }
            }
            assertEquals(1, first, "同 requestId 并发必须恰一个首次受理");
            assertEquals(threads - 1, replay, "其余请求重放首次结果");
            assertEquals(1,
                    jdbc.queryForObject("SELECT COUNT(1) FROM dive WHERE request_id='CONC-IDEM'",
                            Integer.class).intValue());
            assertEquals(1,
                    jdbc.queryForObject("SELECT COUNT(1) FROM idempotency_record "
                            + "WHERE request_id='CONC-IDEM'", Integer.class).intValue());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
