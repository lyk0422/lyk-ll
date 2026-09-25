package com.example.starter.bonded;

import com.example.starter.bonded.dto.ApplyTransferRequest;
import com.example.starter.bonded.dto.ItemRequest;
import com.example.starter.bonded.dto.VerifyItemRequest;
import com.example.starter.bonded.dto.VerifyRequest;
import com.example.starter.bonded.service.TransferService;
import com.example.starter.bonded.web.ApiBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 真实并发测试：核销余量竞争、撤销/核注唯一终态、申请幂等竞争。
 */
class TransferConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private TransferService service;

    private void setupOrder(String requestId, long qty) {
        service.apply(new ApplyTransferRequest(requestId, "ENT-001", "WH-A", "WH-B",
                List.of(new ItemRequest("HS-1001", qty, new BigDecimal("10")))));
        service.endorse(requestId);
        service.depart(requestId);
    }

    private boolean isConflict(Exception e) {
        return e instanceof ApiBusinessException
                && ((ApiBusinessException) e).getStatus().value() == 409;
    }

    @Test
    @DisplayName("并发核销同一商品项：总核销量不得超过余量，超量批次整批拒绝")
    void concurrentVerifyNotExceedQuota() throws Exception {
        setupOrder("REQ-C1", 100);
        int threads = 8;
        long perBatch = 20; // 8 * 20 = 160 > 100，仅 5 个批次应成功
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String batchId = "CB-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                service.verify("REQ-C1", new VerifyRequest(batchId,
                        List.of(new VerifyItemRequest("HS-1001", perBatch))));
                return true;
            }));
        }
        start.countDown();
        int success = 0;
        int rejected = 0;
        for (Future<Boolean> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception e) {
                assertThat(isConflict((Exception) e.getCause())).isTrue();
                rejected++;
            }
        }
        pool.shutdownNow();
        assertThat(success).isEqualTo(5);
        assertThat(rejected).isEqualTo(3);
        // 最终核销量恰好 100，单据结案，冻结库存全部出库
        var view = service.get("REQ-C1");
        assertThat(view.status()).isEqualTo("CLOSED");
        assertThat(view.items().get(0).verifiedQty()).isEqualTo(100L);
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 0L);
    }

    @Test
    @DisplayName("撤销与核注并发：唯一终态，不得出现既撤销又核注（库存不被双扣或悬挂冻结）")
    void cancelVsEndorseRace() throws Exception {
        // 为降低极端调度概率同时验证不变量，重复多轮
        for (int round = 0; round < 10; round++) {
            String requestId = "REQ-R" + round;
            service.apply(new ApplyTransferRequest(requestId, "ENT-001", "WH-A", "WH-B",
                    List.of(new ItemRequest("HS-1001", 10L, new BigDecimal("10")))));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> endorseFuture = pool.submit(() -> {
                start.await();
                service.endorse(requestId);
                return true;
            });
            Future<Boolean> cancelFuture = pool.submit(() -> {
                start.await();
                service.cancel(requestId);
                return true;
            });
            start.countDown();
            boolean endorsed;
            boolean cancelled;
            try {
                endorseFuture.get(10, TimeUnit.SECONDS);
                endorsed = true;
            } catch (Exception e) {
                endorsed = false;
                assertThat(isConflict((Exception) e.getCause())).isTrue();
            }
            try {
                cancelFuture.get(10, TimeUnit.SECONDS);
                cancelled = true;
            } catch (Exception e) {
                cancelled = false;
                assertThat(isConflict((Exception) e.getCause())).isTrue();
            }
            pool.shutdownNow();

            String status = statusOf(requestId);
            assertThat(endorsed || cancelled)
                    .as("第 %d 轮：核注与撤销至少一个成功", round)
                    .isTrue();
            // 唯一终态 + 库存不变量：
            // CANCELLED（含核注先提交后被合法撤销的情形）-> 库存必须完整恢复 (1000,0)，不得悬挂冻结；
            // ENDORSED（撤销失败）-> 库存必须恰好冻结一次 (990,10)，不得双扣。
            assertThat(status).isIn("CANCELLED", "ENDORSED");
            long[] snap = inventoryOf("WH-A", "HS-1001");
            if (status.equals("CANCELLED")) {
                assertThat(snap).as("第 %d 轮：撤销后库存必须恢复", round).containsExactly(1000L, 0L);
            } else {
                assertThat(snap).as("第 %d 轮：核注后库存必须恰好冻结一次", round).containsExactly(990L, 10L);
            }
        }
    }

    @Test
    @DisplayName("并发同 requestId 申请：仅一个成功建单，其余按幂等回放，不产生重复单据")
    void concurrentApplySameRequestId() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                var r = service.apply(new ApplyTransferRequest("REQ-CA", "ENT-001", "WH-A", "WH-B",
                        List.of(new ItemRequest("HS-1001", 5L, new BigDecimal("10")))));
                return r.replayed();
            }));
        }
        start.countDown();
        int created = 0;
        int replayed = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                replayed++;
            } else {
                created++;
            }
        }
        pool.shutdownNow();
        assertThat(created).isEqualTo(1);
        assertThat(replayed).isEqualTo(threads - 1);
        Integer cnt = jdbc().queryForObject(
                "SELECT COUNT(*) FROM transfer_order WHERE request_id = 'REQ-CA'", Integer.class);
        assertThat(cnt).isEqualTo(1);
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return jdbc;
    }
}
