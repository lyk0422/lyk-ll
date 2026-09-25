package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 并发测试：同一文物借展唯一受理；归还点交与续展审批并发形成唯一终态。
 */
class ConcurrencyTest extends MuseumTestSupport {

    @Test
    void concurrentApplyForSameArtifactAllowsExactlyOne() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, "并发申请馆",
                            LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
                    return "OK";
                } catch (ApiException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    return "CONFLICT";
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<String> future : futures) {
            String outcome = future.get(30, TimeUnit.SECONDS);
            if ("OK".equals(outcome)) {
                ok++;
            } else {
                conflict++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok, "同一文物并发借展应唯一受理");
        assertEquals(threads - 1, conflict);
        assertEquals(1, count("loan_contract"));
        assertEquals(1, count("artifact_active_loan"));
    }

    @Test
    void returnHandoverAndExtendRaceYieldsSingleTerminalState() throws Exception {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        // 进入续展受理窗口（届满前第 60 日）
        clock.setInstant(java.time.Instant.parse("2026-11-01T00:00:00Z"));
        // 馆方已完成归还点交确认，借展方确认与续展并发
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_MUSEUM);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        LocalDate newEnd = LocalDate.of(2027, 3, 1);
        Future<String> extendFuture = pool.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                loanService.extend(contractId, newEnd);
                return "OK";
            } catch (ApiException e) {
                assertEquals(HttpStatus.CONFLICT, e.status());
                return "CONFLICT";
            }
        });
        Future<String> returnFuture = pool.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_BORROWER);
                return "OK";
            } catch (ApiException e) {
                assertEquals(HttpStatus.CONFLICT, e.status());
                return "CONFLICT";
            }
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        String extendOutcome = extendFuture.get(30, TimeUnit.SECONDS);
        String returnOutcome = returnFuture.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 唯一终态：续展与归还点交有且仅有一个生效
        assertTrue(!"OK".equals(extendOutcome) || !"OK".equals(returnOutcome),
                "不得既归还又续展");
        Map<String, Object> contract = loanService.get(contractId);
        if ("OK".equals(extendOutcome)) {
            assertEquals(LoanService.STATUS_ON_DISPLAY, contract.get("status"));
            assertEquals(true, contract.get("extended"));
            assertEquals(newEnd.toString(), contract.get("endDate"));
        } else {
            assertEquals("OK", returnOutcome);
            assertEquals(LoanService.STATUS_RETURNED, contract.get("status"));
            assertEquals(false, contract.get("extended"));
        }
    }
}
