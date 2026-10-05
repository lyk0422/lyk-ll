package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;

/**
 * 接力押运并发边界：同一运单同时刻唯一进行中接力段；换班完成与禁行冻结并发形成唯一终态。
 *
 * <p>合成夜间禁行时段：2026-10-06 00:00（含）起。R-G3 发运 2026-10-05 22:00 跨越禁行起点。
 */
class RelayConcurrentTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-05T22:00:00");
    private static final LocalDateTime CURFEW_START = LocalDateTime.parse("2026-10-06T00:00:00");

    private String approvedCrossing(String reqPrefix, String plate) {
        String no = shipmentNo(apply(reqPrefix + "-apply", plate, "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        return no;
    }

    @Test
    void concurrentRelayCreationExactlyOneOpenSegment() throws Exception {
        String no = approvedCrossing("cc-relay", "沪A-1001");
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger activeRejected = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        setRelay(no, "cc-relay-" + idx, 2);
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        if ("RELAY_ACTIVE".equals(e.getCode())) {
                            activeRejected.incrementAndGet();
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

        // 同一运单同时刻只允许一个进行中接力段：恰一个成功，其余 RELAY_ACTIVE。
        assertThat(success.get()).isEqualTo(1);
        assertThat(activeRejected.get()).isEqualTo(threads - 1);
        assertThat(otherErrors.get()).isZero();
        Integer openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM relay_segment WHERE shipment_id = (SELECT id FROM shipment WHERE shipment_no = ?) AND status = 'OPEN'",
                Integer.class, no);
        assertThat(openCount).isEqualTo(1);
        Integer activeCount = jdbc.queryForObject("SELECT COUNT(*) FROM shipment_active_relay", Integer.class);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void concurrentHandoverAndCurfewFreezeFormUniqueTerminalOutcome() throws Exception {
        String no = approvedCrossing("cc-race", "沪A-1001");
        setRelay(no, "cc-race-relay", 2);
        dispatch(no, "cc-race-dispatch");
        // 交班已打卡，接班打卡（23:55，可完成换班）与禁行起点冻结扫描并发竞争。
        relayCheckin(no, "cc-race-out", "ESC-OUT-01", "OUTGOING",
                LocalDateTime.parse("2026-10-05T23:50:00"));

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<String> checkinOutcome = new AtomicReference<>();
        AtomicReference<String> scanOutcome = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                try {
                    start.await();
                    relayCheckin(no, "cc-race-in", "ESC-IN-02", "INCOMING",
                            LocalDateTime.parse("2026-10-05T23:55:00"));
                    checkinOutcome.set("COMPLETED");
                } catch (ApiException e) {
                    checkinOutcome.set(e.getCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    scan(CURFEW_START);
                    scanOutcome.set("SCAN_OK");
                } catch (ApiException e) {
                    scanOutcome.set(e.getCode());
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

        Map<String, Object> view = view(no);
        @SuppressWarnings("unchecked")
        Map<String, Object> relay = (Map<String, Object>) view.get("relay");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disposals = (List<Map<String, Object>>) view.get("disposals");

        boolean handoverWon = "COMPLETED".equals(relay.get("status"));
        boolean freezeWon = "CURFEW_FROZEN".equals(view.get("status"));
        // 唯一终态：换班完成与禁行冻结不得同时生效，恰一方胜出。
        assertThat(handoverWon).isNotEqualTo(freezeWon);
        if (freezeWon) {
            assertThat(relay.get("status")).isEqualTo("BREACHED");
            assertThat(disposals).hasSize(1);
            assertThat(checkinOutcome.get()).isEqualTo("SHIPMENT_TERMINAL");
            assertThat(scanOutcome.get()).isEqualTo("SCAN_OK");
        } else {
            assertThat(view.get("status")).isEqualTo("DISPATCHED");
            assertThat(disposals).isEmpty();
            assertThat(checkinOutcome.get()).isEqualTo("COMPLETED");
            // 扫描方要么空扫成功，要么因并发完成回滚本次冻结。
            assertThat(scanOutcome.get()).isIn("SCAN_OK", "RELAY_COMPLETED_CONCURRENTLY");
        }
        // 数据自洽：运单终态标志与状态一致，事件不重复。
        Integer terminalFlag = jdbc.queryForObject(
                "SELECT is_terminal FROM shipment WHERE shipment_no = ?", Integer.class, no);
        assertThat(terminalFlag).isEqualTo(freezeWon ? 1 : 0);
        assertThat(events(view).stream()
                .filter(e -> "CURFEW_FROZEN".equals(e.get("eventType"))).count())
                .isEqualTo(freezeWon ? 1 : 0);
    }
}
