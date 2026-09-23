package com.example.starter.rail;

import com.example.starter.rail.dto.CreateRequestRequest;
import com.example.starter.rail.service.PossessionService;
import com.example.starter.rail.web.ApprovalConflictException;
import com.example.starter.rail.web.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实并发 H2 事务测试：同区段争抢只能一笔成功；无共同区段可并行成功。
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private PossessionService service;

    private CreateRequestRequest request(String id, List<String> sections) {
        return new CreateRequestRequest(id, sections,
                Instant.ofEpochMilli(at(10_000)), Instant.ofEpochMilli(at(20_000)),
                0, 0, 1);
    }

    @Test
    void two_approvals_contending_same_section_exactly_one_succeeds() throws Exception {
        service.create(request("C1", List.of("S1")));
        service.create(request("C2", List.of("S1")));

        int successes = runConcurrently(
                () -> service.approve("C1"),
                () -> service.approve("C2"));

        assertThat(successes).isEqualTo(1);
        int approved = 0;
        if ("APPROVED".equals(service.get("C1").status())) {
            approved++;
        }
        if ("APPROVED".equals(service.get("C2").status())) {
            approved++;
        }
        assertThat(approved).isEqualTo(1);
    }

    @Test
    void approvals_without_shared_sections_both_succeed() throws Exception {
        service.create(request("P1", List.of("S9")));
        service.create(request("P2", List.of("S8")));

        int successes = runConcurrently(
                () -> service.approve("P1"),
                () -> service.approve("P2"));

        assertThat(successes).isEqualTo(2);
        assertThat(service.get("P1").status()).isEqualTo("APPROVED");
        assertThat(service.get("P2").status()).isEqualTo("APPROVED");
    }

    @Test
    void multi_section_contention_still_only_one_wins() throws Exception {
        // 两个申请区段集合部分重叠（S2），必须串行且只能一笔成功。
        service.create(request("M1", List.of("S1", "S2")));
        service.create(request("M2", List.of("S2", "S3")));

        int successes = runConcurrently(
                () -> service.approve("M1"),
                () -> service.approve("M2"));

        assertThat(successes).isEqualTo(1);
    }

    /**
     * 在同一发令枪下并发执行两个审批，返回成功笔数；
     * 失败者必须是冲突类异常（409 语义），不得是其他错误。
     */
    private int runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> f1 = pool.submit(() -> attempt(first, ready, start));
            Future<?> f2 = pool.submit(() -> attempt(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (Future<?> f : List.of(f1, f2)) {
                Object result = f.get(30, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(result)) {
                    successes++;
                }
            }
            return successes;
        } finally {
            pool.shutdownNow();
        }
    }

    private Boolean attempt(Runnable action, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            action.run();
            return Boolean.TRUE;
        } catch (ApprovalConflictException | ConflictException expected) {
            // 争抢失败者：冲突拒绝，事务回滚。
            return Boolean.FALSE;
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
