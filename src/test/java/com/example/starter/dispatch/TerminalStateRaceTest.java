package com.example.starter.dispatch;

import com.example.starter.dispatch.api.ApiException;
import com.example.starter.dispatch.api.dto.OrderView;
import com.example.starter.dispatch.domain.OrderStatus;
import com.example.starter.dispatch.support.RaceClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.starter.dispatch.support.Requests.gate;
import static com.example.starter.dispatch.support.Requests.issue;
import static com.example.starter.dispatch.support.Requests.receipt;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终态竞态（真实并发，H2 行锁 + 条件更新）：
 * <ul>
 *   <li>超时升级与最后一门回执并发到达：回执时刻恰为执行时限点（按时），
 *       扫描时刻为时限后 1ms，二者并发，终态由行锁裁决，不得既超时又完成；</li>
 *   <li>撤销与签收并发：只形成唯一结果，占用与时间戳互不矛盾。</li>
 * </ul>
 */
class TerminalStateRaceTest extends AbstractDispatchTest {

    @Autowired
    private RaceClock raceClock;

    /**
     * 多轮同时触发“超时扫描”和“最后一门回执”。
     * 回执线程在 deadline 点到达（按时，应可完成），扫描线程在 deadline+1ms 触发（应升级）；
     * 两线程在同一栅栏释放后竞争同一令行锁：
     * 回执先得锁 => COMPLETED（扫描跳过，无超时告警）；
     * 扫描先得锁 => TIMEOUT（回执 409，无实际开度残留）。
     */
    @Test
    void timeout_and_final_receipt_race_produces_exactly_one_terminal_state() throws Exception {
        int rounds = 30;
        AtomicInteger completedWins = new AtomicInteger();
        AtomicInteger timeoutWins = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                String requestId = "REQ-RACE-T-" + i;
                var deadline = BASE.plusSeconds(60);
                service.issue(issue(requestId, deadline, gate("GATE-A", 30)));
                service.accept(requestId);
                raceClock.beginRace(deadline);

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<OrderView> receiptFuture = pool.submit(() -> {
                    raceClock.asRole(RaceClock.Role.RECEIPT);
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        return service.receipt(requestId, receipt("GATE-A", 30.0));
                    } finally {
                        raceClock.clearRole();
                    }
                });
                Future<List<OrderView>> scanFuture = pool.submit(() -> {
                    raceClock.asRole(RaceClock.Role.SCAN);
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        return service.scanTimeouts();
                    } finally {
                        raceClock.clearRole();
                    }
                });

                boolean receiptSucceeded;
                try {
                    receiptFuture.get(10, TimeUnit.SECONDS);
                    receiptSucceeded = true;
                } catch (Exception e) {
                    receiptSucceeded = false;
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) e.getCause()).getCode()).isEqualTo("ORDER_TIMEOUT");
                }
                List<OrderView> escalated = scanFuture.get(10, TimeUnit.SECONDS);
                boolean scanWon = escalated.stream().anyMatch(v -> v.requestId().equals(requestId));

                OrderView finalView = service.get(requestId);

                // 核心不变量：恰好一方胜出，绝不既超时又完成
                assertThat(receiptSucceeded).isNotEqualTo(scanWon);
                if (receiptSucceeded) {
                    completedWins.incrementAndGet();
                    assertThat(finalView.status()).isEqualTo(OrderStatus.COMPLETED);
                    assertThat(finalView.escalated()).isFalse();
                    assertThat(finalView.timeoutAt()).isNull();
                    assertThat(finalView.completedAt()).isEqualTo(deadline);
                    assertThat(finalView.gates().get(0).actualOpening()).isNotNull();
                    assertThat(finalView.alarms()).isEmpty();
                } else {
                    timeoutWins.incrementAndGet();
                    assertThat(finalView.status()).isEqualTo(OrderStatus.TIMEOUT);
                    assertThat(finalView.escalated()).isTrue();
                    assertThat(finalView.timeoutAt()).isEqualTo(deadline.plusMillis(1));
                    assertThat(finalView.completedAt()).isNull();
                    assertThat(finalView.gates().get(0).actualOpening()).isNull();
                    assertThat(finalView.alarms()).hasSize(1);
                    assertThat(finalView.alarms().get(0).type()).isEqualTo("TIMEOUT_ESCALATION");
                }
                // 终态必然释放闸门占用
                assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM gate_occupancy WHERE gate_code = 'GATE-A'", Integer.class))
                        .isZero();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(completedWins.get() + timeoutWins.get()).isEqualTo(rounds);
        // 多轮真实竞争下两种裁决顺序都应实际出现，作为竞态被真实触发且双向均正确的证据
        assertThat(completedWins.get()).isGreaterThan(0);
        assertThat(timeoutWins.get()).isGreaterThan(0);
    }

    /**
     * 撤销与签收在 ISSUED 令上并发：仅一方生效。
     * 签收胜 => ACCEPTED（撤销 409，占用保留）；撤销胜 => CANCELLED（签收 409，占用释放）。
     */
    @Test
    void cancel_and_accept_race_produces_unique_result() throws Exception {
        int rounds = 30;
        AtomicInteger acceptWins = new AtomicInteger();
        AtomicInteger cancelWins = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                String requestId = "REQ-RACE-C-" + i;
                service.issue(issue(requestId, BASE.plusSeconds(300), gate("GATE-B", 30)));

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<OrderView> acceptFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.accept(requestId);
                });
                Future<OrderView> cancelFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.cancel(requestId);
                });

                boolean acceptSucceeded;
                try {
                    acceptFuture.get(10, TimeUnit.SECONDS);
                    acceptSucceeded = true;
                } catch (Exception e) {
                    acceptSucceeded = false;
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) e.getCause()).getCode()).isEqualTo("ORDER_CANCELLED");
                }
                boolean cancelSucceeded;
                try {
                    cancelFuture.get(10, TimeUnit.SECONDS);
                    cancelSucceeded = true;
                } catch (Exception e) {
                    cancelSucceeded = false;
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) e.getCause()).getCode()).isEqualTo("ALREADY_ACCEPTED");
                }

                OrderView finalView = service.get(requestId);
                assertThat(acceptSucceeded).isNotEqualTo(cancelSucceeded);
                if (acceptSucceeded) {
                    acceptWins.incrementAndGet();
                    assertThat(finalView.status()).isEqualTo(OrderStatus.ACCEPTED);
                    assertThat(finalView.acceptedAt()).isNotNull();
                    assertThat(finalView.cancelledAt()).isNull();
                    assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM gate_occupancy WHERE gate_code = 'GATE-B'", Integer.class))
                            .isEqualTo(1);
                } else {
                    cancelWins.incrementAndGet();
                    assertThat(finalView.status()).isEqualTo(OrderStatus.CANCELLED);
                    assertThat(finalView.cancelledAt()).isNotNull();
                    assertThat(finalView.acceptedAt()).isNull();
                    assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM gate_occupancy WHERE gate_code = 'GATE-B'", Integer.class))
                            .isZero();
                }
                jdbc.update("DELETE FROM gate_occupancy");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(acceptWins.get() + cancelWins.get()).isEqualTo(rounds);
        assertThat(acceptWins.get()).isGreaterThan(0);
        assertThat(cancelWins.get()).isGreaterThan(0);
    }
}
