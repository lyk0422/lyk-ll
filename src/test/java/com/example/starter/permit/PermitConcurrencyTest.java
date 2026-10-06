package com.example.starter.permit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.common.ApiException;
import com.example.starter.permit.PermitDtos.IssuePermitRequest;
import com.example.starter.permit.PermitDtos.PermitResponse;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.tree.TreeDtos.TreeResponse;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 并发唯一测试：同一路段同时刻仅允许一张执行中许可。
 */
class PermitConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private PermitService permitService;

    @Test
    void onlyOneExecutingPermitPerSegmentUnderConcurrency() throws Exception {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree1 = registerLevelOneTree(segment.id(), "T-001");
        TreeResponse tree2 = registerLevelOneTree(segment.id(), "T-002");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> issue1 = () -> issueWhenReady(tree1.taskId(), "req-c-1", ready, start);
            Callable<Object> issue2 = () -> issueWhenReady(tree2.taskId(), "req-c-2", ready, start);
            Future<Object> f1 = pool.submit(issue1);
            Future<Object> f2 = pool.submit(issue2);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Object r1 = f1.get(15, TimeUnit.SECONDS);
            Object r2 = f2.get(15, TimeUnit.SECONDS);

            long successes = java.util.stream.Stream.of(r1, r2)
                    .filter(r -> r instanceof PermitResponse).count();
            long conflicts = java.util.stream.Stream.of(r1, r2)
                    .filter(r -> r instanceof ApiException
                            && ((ApiException) r).status() == HttpStatus.CONFLICT)
                    .count();
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);

            Long executing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM work_permit WHERE status = 'EXECUTING'", Long.class);
            assertThat(executing).isEqualTo(1);
            Long occupied = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM segment_active_permit", Long.class);
            assertThat(occupied).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Object issueWhenReady(long taskId, String requestId,
            CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return permitService.issue(new IssuePermitRequest(requestId, taskId, "AERIAL"));
        } catch (ApiException e) {
            return e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
