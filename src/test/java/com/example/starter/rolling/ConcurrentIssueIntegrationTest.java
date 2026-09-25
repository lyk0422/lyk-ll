package com.example.starter.rolling;

import com.example.starter.rolling.api.dto.IssueRequest;
import com.example.starter.rolling.api.dto.ScheduleView;
import com.example.starter.rolling.service.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同产线并发下达：靠 effective_version 唯一约束/条件更新裁决，唯一生效。
 */
class ConcurrentIssueIntegrationTest extends AbstractRollingIntegrationTest {

    @Test
    void 两个首版并发下达仅一个成功() throws Exception {
        ScheduleView a = preparedDraft("req-a", "LC", items(item("A1", 1250, "3", true, 0)));
        ScheduleView b = preparedDraft("req-b", "LC", items(item("B1", 1250, "3", true, 2)));

        List<IssueOutcome> outcomes = runConcurrentIssue(
                new IssueTask(a.id(), "issue-a"),
                new IssueTask(b.id(), "issue-b"));

        long success = outcomes.stream().filter(o -> o.success).count();
        long conflict = outcomes.stream().filter(o -> o.conflict).count();
        assertThat(success).as("唯一下达成功").isEqualTo(1);
        assertThat(conflict).as("另一个收到 409").isEqualTo(1);

        Long effectiveId = jdbc.queryForObject(
                "SELECT order_id FROM effective_version WHERE line_code = 'LC'", Long.class);
        Long effectiveCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM effective_version WHERE line_code = 'LC'", Long.class);
        assertThat(effectiveCount).isEqualTo(1);

        ScheduleView effective = service.get(effectiveId);
        assertThat(effective.effective()).isTrue();
        assertThat(effective.status()).isEqualTo("ISSUED");
        ScheduleView loser = service.get(successId(a, b, effectiveId));
        assertThat(loser.effective()).isFalse();
        assertThat(loser.status()).isEqualTo("VALIDATED");
    }

    @Test
    void 两个修改版并发下达仅一个生效旧版本被替换() throws Exception {
        ScheduleView v1 = draftThenValidateThenIssue("req-base", "LR",
                items(item("M1", 1250, "3", true, 0)));

        ScheduleView v2 = preparedRevise("req-v2", v1.id(),
                items(item("M1", 1250, "2", true, 0), item("M2", 1200, "2", true, 1)));
        ScheduleView v3 = preparedRevise("req-v3", v1.id(),
                items(item("M1", 1250, "1", true, 0), item("M3", 1200, "1", true, 1)));

        List<IssueOutcome> outcomes = runConcurrentIssue(
                new IssueTask(v2.id(), "issue-v2"),
                new IssueTask(v3.id(), "issue-v3"));

        assertThat(outcomes.stream().filter(o -> o.success).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o.conflict).count()).isEqualTo(1);

        Long effectiveId = jdbc.queryForObject(
                "SELECT order_id FROM effective_version WHERE line_code = 'LR'", Long.class);
        assertThat(effectiveId).isIn(v2.id(), v3.id());
        assertThat(service.get(v1.id()).effective()).isFalse();
    }

    private ScheduleView preparedDraft(String requestId, String line,
                                       List<com.example.starter.rolling.api.dto.ScheduleItemRequest> items) {
        ScheduleView draft = service.createOrRevise(simpleOrder(requestId, line, items));
        service.validate(draft.id());
        return draft;
    }

    private ScheduleView preparedRevise(String requestId, long baseId,
                                        List<com.example.starter.rolling.api.dto.ScheduleItemRequest> items) {
        ScheduleView draft = service.createOrRevise(
                createRequest(requestId, "LR", "U1", baseId, items, List.of()));
        service.validate(draft.id());
        return draft;
    }

    private long successId(ScheduleView a, ScheduleView b, long effectiveId) {
        return a.id() == effectiveId ? b.id() : a.id();
    }

    private record IssueTask(long orderId, String requestId) {
    }

    private record IssueOutcome(boolean success, boolean conflict, String error) {
    }

    private List<IssueOutcome> runConcurrentIssue(IssueTask... tasks) throws Exception {
        int n = tasks.length;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<IssueOutcome>> futures = new ArrayList<>();
            for (IssueTask task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                    try {
                        service.issue(task.orderId(), new IssueRequest(task.requestId()));
                        return new IssueOutcome(true, false, null);
                    } catch (ConflictException e) {
                        return new IssueOutcome(false, true, e.getMessage());
                    } catch (Exception e) {
                        return new IssueOutcome(false, false, e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<IssueOutcome> outcomes = new ArrayList<>();
            for (Future<IssueOutcome> f : futures) {
                IssueOutcome outcome = f.get(30, TimeUnit.SECONDS);
                assertThat(outcome.success() || outcome.conflict())
                        .as("并发下达只允许成功或 409，实际：%s", outcome.error()).isTrue();
                outcomes.add(outcome);
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
