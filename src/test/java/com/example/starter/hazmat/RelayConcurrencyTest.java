package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;

/**
 * 夜间接力并发边界：同一运单同时刻唯一进行中接力段；禁行冻结与核销竞争唯一终态。
 *
 * <p>R-N3 傍晚 20:00 发运，禁行起点 2026-10-03T00:00。
 */
class RelayConcurrencyTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T20:00:00");
    private static final LocalDateTime CURFEW_START = LocalDateTime.parse("2026-10-03T00:00:00");

    private String approvedNightShipment(String reqPrefix) {
        String no = shipmentNo(apply(reqPrefix + "-apply", "沪C-3002", "EXPLOSIVE", "R-N3", DEPARTURE));
        approve(no);
        return no;
    }

    @Test
    void concurrentRelayCreateExactlyOneSucceeds() throws Exception {
        String no = approvedNightShipment("cc-relay");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger inProgressRejected = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        createRelay(no, "cc-relay-" + idx, 2, "ESC-A", "ESC-B");
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        if ("RELAY_IN_PROGRESS".equals(e.getCode())) {
                            inProgressRejected.incrementAndGet();
                        } else {
                            otherErrors.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(inProgressRejected.get()).isEqualTo(threads - 1);
        assertThat(otherErrors.get()).isZero();
        // 终态数据自洽：进行中接力段占用恰一行，接力段恰一条。
        assertThat(repository.findActiveRelay(
                repository.findShipmentByNo(no).orElseThrow().id())).isPresent();
        assertThat(relays(view(no))).hasSize(1);
    }

    @Test
    void curfewFreezeAndCompleteRaceUniqueTerminal() throws Exception {
        String no = approvedNightShipment("race");
        long relayId = ((Number) createRelay(no, "race-relay", 2, "ESC-A", "ESC-B")
                .body().get("relayId")).longValue();
        dispatch(no, "race-dispatch");

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger frozen = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                try {
                    start.await();
                    scan(CURFEW_START);
                    frozen.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    complete(no, "race-complete");
                    completed.incrementAndGet();
                } catch (ApiException e) {
                    // 终态竞争败者：禁行冻结/失联冲突拒绝，属预期分支。
                    if (!"ALREADY_CURFEW_FROZEN".equals(e.getCode())
                            && !"ALREADY_LOST_CONTACT".equals(e.getCode())) {
                        unexpected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }

        assertThat(unexpected.get()).isZero();
        // 唯一终态：恰为 COMPLETED 或 CURFEW_FROZEN 之一，不可能既冻结又核销。
        Map<String, Object> v = view(no);
        assertThat(v.get("terminal")).isEqualTo(true);
        assertThat(v.get("status")).isIn("COMPLETED", "CURFEW_FROZEN");
        if ("CURFEW_FROZEN".equals(v.get("status"))) {
            // 冻结与处置登记同事务：冻结终态必有且仅有一条处置记录。
            assertThat(disposals(v)).hasSize(1);
            assertThat(relay(no, relayId).get("status")).isEqualTo("FROZEN");
        } else {
            assertThat(disposals(v)).isEmpty();
            assertThat(completed.get()).isEqualTo(1);
        }
        Integer terminalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipment WHERE shipment_no = ? AND is_terminal = 1",
                Integer.class, no);
        assertThat(terminalCount).isEqualTo(1);
    }
}
