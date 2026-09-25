package com.example.starter.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.order.OrderDtos.IssueRequest;
import com.example.starter.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 并发测试：同一间隔同时刻只允许一张执行中操作票，并发预发唯一成功。
 */
class ConcurrentIssueTest extends BaseIntegrationTest {

    @Test
    void 并发预发同间隔_唯一成功() throws Exception {
        long orderA = draft("conc-draft-a", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        long orderB = draft("conc-draft-b", "INT-1101",
                step(1, "DIS-1101", "OPEN_DISCONNECTOR", "OPEN"));
        review(orderA);
        review(orderB);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ResponseEntity<String>> futureA = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return orderService.issue(orderA, new IssueRequest("conc-issue-a"));
            });
            Future<ResponseEntity<String>> futureB = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return orderService.issue(orderB, new IssueRequest("conc-issue-b"));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(
                    futureA.get(30, TimeUnit.SECONDS).getStatusCode().value(),
                    futureB.get(30, TimeUnit.SECONDS).getStatusCode().value());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        // 最终数据：同间隔仅一张执行中操作票
        assertThat(count("switching_order", "executing_interval = 'INT-1101'")).isEqualTo(1);
        assertThat(count("switching_order", "status = 'EXECUTING'")).isEqualTo(1);
        assertThat(count("switching_order", "status = 'REVIEWED'")).isEqualTo(1);
    }

    @Test
    void 串行预发同间隔_第二张409() {
        long orderA = draftReviewIssue("serial-a", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        long orderB = draft("serial-b-draft", "INT-1101",
                step(1, "DIS-1101", "OPEN_DISCONNECTOR", "OPEN"));
        review(orderB);

        assertThat(issue(orderB, "serial-b-issue")).isEqualTo(409);
        assertThat(orderStatus(orderA)).isEqualTo("EXECUTING");
        assertThat(orderStatus(orderB)).isEqualTo("REVIEWED");
    }
}
