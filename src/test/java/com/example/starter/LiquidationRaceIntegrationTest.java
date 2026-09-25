package com.example.starter;

import com.example.starter.dto.ApiDtos;
import com.example.starter.error.ApiException;
import com.example.starter.service.AccountService;
import com.example.starter.service.DepositService;
import com.example.starter.service.LiquidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入账与强平执行竞态测试：两者通过账户行级悲观锁互斥，先到达者决定结果。
 * 入账先到：比例达标撤销待发强平，执行方得到 409；
 * 强平先到：按当时负债平仓即止，迟到入账计入余下负债重算。
 * 两种结果都不得出现“既全额强平又保留足额保证金”。
 */
class LiquidationRaceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private LiquidationService liquidationService;

    private sealed interface Outcome {
        record DepositOk(ApiDtos.DepositResponse response) implements Outcome {
        }

        record ExecuteOk(ApiDtos.LiquidationView view) implements Outcome {
        }

        record Failed(ApiException error) implements Outcome {
        }
    }

    @Test
    void depositVsLiquidationExecution_firstArriverDecides() throws Exception {
        // 多轮真实并发，覆盖两种到达顺序
        for (int round = 0; round < 10; round++) {
            long accountId = accountService.createAccount(
                    new ApiDtos.CreateAccountRequest("RACE-" + round, "CNY")).id();
            // 比例 129%：触发追保；逾期后生成待发强平指令
            accountService.confirmSnapshot(accountId,
                    new ApiDtos.SnapshotRequest("129.00", List.of("100.00")));
            var due = liquidationService.processDue(LocalDateTime.of(2026, 9, 28, 15, 1));
            assertEquals(1, due.createdOrderIds().size());
            long orderId = due.createdOrderIds().get(0);
            final String requestId = "RACE-D-" + round;

            CountDownLatch gate = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Outcome> depositFuture = pool.submit(() -> {
                    gate.await();
                    try {
                        return new Outcome.DepositOk(depositService.deposit(accountId,
                                new ApiDtos.DepositRequest(requestId, "11.00", "CNY")));
                    } catch (ApiException e) {
                        return new Outcome.Failed(e);
                    }
                });
                Future<Outcome> executeFuture = pool.submit(() -> {
                    gate.await();
                    try {
                        return new Outcome.ExecuteOk(liquidationService.execute(orderId));
                    } catch (ApiException e) {
                        return new Outcome.Failed(e);
                    }
                });
                gate.countDown();
                Outcome depositOutcome = depositFuture.get(30, TimeUnit.SECONDS);
                Outcome executeOutcome = executeFuture.get(30, TimeUnit.SECONDS);

                var view = accountService.view(accountId);
                if (executeOutcome instanceof Outcome.Failed failed) {
                    // 入账先到：强平被撤销，执行方 409；资产 140.00、负债 100.00 原样保留
                    assertEquals(409, failed.error().status().value());
                    assertTrue(depositOutcome instanceof Outcome.DepositOk);
                    assertEquals("CANCELLED", liquidationService.view(orderId).status());
                    assertEquals("140.00", view.totalAssets());
                    assertEquals("100.00", view.totalLiabilities());
                } else {
                    // 强平先到：仅平 27.50 即止；迟到入账 11.00 计入余下负债重算
                    var exec = (Outcome.ExecuteOk) executeOutcome;
                    assertEquals("DONE", exec.view().status());
                    assertEquals(1, exec.view().items().size());
                    assertEquals("27.50", exec.view().items().get(0).amount());
                    assertTrue(depositOutcome instanceof Outcome.DepositOk);
                    assertEquals("112.50", view.totalAssets());
                    assertEquals("72.50", view.totalLiabilities());
                    // 不得既全额强平又保留足额保证金：余下负债未被多平
                    assertTrue(queryLong("SELECT COUNT(*) FROM liability WHERE account_id = ?"
                            + " AND status = 'OPEN' AND outstanding = 7250", accountId) == 1);
                }
                // 两种结果下入账均恰好入账一次
                assertEquals(1, queryLong("SELECT COUNT(*) FROM deposit WHERE request_id = ?",
                        requestId));
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }
}
