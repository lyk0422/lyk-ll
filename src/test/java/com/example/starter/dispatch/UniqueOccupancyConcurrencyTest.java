package com.example.starter.dispatch;

import com.example.starter.dispatch.api.ApiException;
import com.example.starter.dispatch.api.dto.IssueOrderRequest;
import com.example.starter.dispatch.api.dto.OrderView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.starter.dispatch.support.Requests.gate;
import static com.example.starter.dispatch.support.Requests.issue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界：同一闸门同一时刻只允许一张未完成调度令，唯一索引兜底并发下达。
 */
class UniqueOccupancyConcurrencyTest extends AbstractDispatchTest {

    private final Instant deadline = BASE.plusSeconds(300);

    @Test
    void concurrent_issues_for_same_gate_allow_only_one_active_order() throws Exception {
        IssueOrderRequest req1 = issue("REQ-CONC-G-1", deadline, gate("GATE-A", 30));
        IssueOrderRequest req2 = issue("REQ-CONC-G-2", deadline, gate("GATE-A", 35));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OrderView> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.issue(req1);
            });
            Future<OrderView> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.issue(req2);
            });

            int success = 0;
            int conflict = 0;
            for (Future<OrderView> f : List.of(f1, f2)) {
                try {
                    OrderView view = f.get(10, TimeUnit.SECONDS);
                    assertThat(view.status().isTerminal()).isFalse();
                    success++;
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    assertThat(cause).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) cause).getCode()).isEqualTo("GATE_OCCUPIED");
                    conflict++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflict).isEqualTo(1);

            // 数据库中该闸门只有一条占用；被拒令未残留
            Integer occupancyCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gate_occupancy WHERE gate_code = 'GATE-A'", Integer.class);
            assertThat(occupancyCount).isEqualTo(1);
            Integer orderCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dispatch_order WHERE request_id IN ('REQ-CONC-G-1','REQ-CONC-G-2')",
                    Integer.class);
            assertThat(orderCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrent_issues_with_same_request_id_both_return_first_result() throws Exception {
        IssueOrderRequest request = issue("REQ-CONC-IDEMP", deadline, gate("GATE-B", 50));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OrderView> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.issue(request);
            });
            Future<OrderView> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.issue(request);
            });

            OrderView v1 = f1.get(10, TimeUnit.SECONDS);
            OrderView v2 = f2.get(10, TimeUnit.SECONDS);
            assertThat(v1.requestId()).isEqualTo("REQ-CONC-IDEMP");
            assertThat(v2.requestId()).isEqualTo("REQ-CONC-IDEMP");
            assertThat(v1).usingRecursiveComparison().isEqualTo(v2);

            Integer orderCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dispatch_order WHERE request_id = 'REQ-CONC-IDEMP'",
                    Integer.class);
            assertThat(orderCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void gate_can_be_dispatched_again_after_previous_order_completes() {
        service.issue(issue("REQ-REUSE-1", deadline, gate("GATE-C", 20)));
        service.accept("REQ-REUSE-1");
        service.receipt("REQ-REUSE-1", com.example.starter.dispatch.support.Requests.receipt("GATE-C", 20.0));

        OrderView second = service.issue(issue("REQ-REUSE-2", deadline.plusSeconds(60), gate("GATE-C", 80)));
        assertThat(second.status()).isNotNull();
        Integer occupancyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gate_occupancy WHERE gate_code = 'GATE-C'", Integer.class);
        assertThat(occupancyCount).isEqualTo(1);
    }
}
