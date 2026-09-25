package com.example.starter.epidemic;

import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.ReviewRequest;
import com.example.starter.epidemic.api.SubmitCardRequest;
import com.example.starter.epidemic.api.WithdrawRequest;
import com.example.starter.epidemic.domain.CardStatus;
import com.example.starter.epidemic.service.ApiException;
import com.example.starter.epidemic.service.EpidemicCardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 真实并发测试：初审通过与撤回并发时必须形成唯一终态；并发重复报告由唯一约束裁决只建一张卡。
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    @Test
    void concurrentReviewAndWithdraw_singleTerminalState() throws Exception {
        CardResponse card = service.submit(submitRequest("req-race-init", "P-RACE", "B02",
                BASE_TIME.minusSeconds(3600)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<CardStatus> winner = new AtomicReference<>();
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<?>> futures = new ArrayList<>();

            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                service.review(card.cardNo(), new ReviewRequest("auditor-01", "并发初审"));
                winner.compareAndSet(null, CardStatus.PENDING_FINAL);
                return null;
            }));
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                service.withdraw(card.cardNo(), new WithdrawRequest("reporter-1", "并发撤回"));
                winner.compareAndSet(null, CardStatus.WITHDRAWN);
                return null;
            }));

            int success = 0;
            int rejected = 0;
            for (Future<?> future : futures) {
                try {
                    future.get(15, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    assertTrue(e.getCause() instanceof ApiException,
                            "落败动作须为业务异常，实际: " + e.getCause());
                    rejected++;
                }
            }

            assertEquals(1, success, "初审与撤回并发只能有一个动作成功");
            assertEquals(1, rejected, "另一个动作必须被拒绝");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM card", Integer.class));

            CardStatus finalState = service.getCard(card.cardNo()).status();
            assertEquals(winner.get(), finalState, "数据库终态必须与胜出动作一致");
            assertTrue(finalState == CardStatus.PENDING_FINAL
                            || finalState == CardStatus.WITHDRAWN,
                    "终态只能是待终审或已撤回");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateSubmissions_onlyOneCardCreated() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<CardResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    SubmitCardRequest req = submitRequest("req-dup-conc-" + idx, "P-DUP-CONC",
                            "B02", BASE_TIME.minusSeconds(1800));
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.submit(req);
                }));
            }

            CardResponse r1 = futures.get(0).get(15, TimeUnit.SECONDS);
            CardResponse r2 = futures.get(1).get(15, TimeUnit.SECONDS);
            assertEquals(r1.cardNo(), r2.cardNo(), "并发重复报告必须指向同一张卡");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM card", Integer.class),
                    "唯一约束须保证只有一张有效卡");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM card_version WHERE card_no = ?", Integer.class,
                    r1.cardNo()));
        } finally {
            pool.shutdownNow();
        }
    }
}
